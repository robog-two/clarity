#!/usr/bin/env python3
"""renderd: turns a report revision (Markdown + style spec) into a file with pandoc.

POST /render  {"source": "...", "style": {...}, "format": "pdf|docx|odt|txt|md"}  -> the file's bytes
GET  /healthz -> {"status": "ok", "version": "..."}

Bearer key from RENDER_KEY_FILE on /render (refused when the file is missing or empty). No network:
the container sits only on the backend's internal `render` network. Word and OpenDocument styling
comes from pandoc's own reference documents, patched per style at render time (fonts, sizes, line
spacing, indents, margins); PDFs go through Typst. Python standard library only, so the image needs
nothing but python3, pandoc, typst and the fonts.
"""

import hashlib
import hmac
import io
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = int(os.environ.get("RENDER_PORT", "8950"))
KEY_FILE = os.environ.get("RENDER_KEY_FILE", "/run/secrets/render_key")
TIMEOUT = float(os.environ.get("RENDER_TIMEOUT", "60"))
MAX_BODY = int(os.environ.get("RENDER_MAX_BODY", str(4 * 1024 * 1024)))
MAX_PARALLEL = int(os.environ.get("RENDER_MAX_PARALLEL", "2"))
PANDOC = os.environ.get("RENDER_PANDOC", "pandoc")
DATA = os.environ.get("RENDER_DATA", HERE)

FORMATS = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "odt": "application/vnd.oasis.opendocument.text",
    "txt": "text/plain; charset=utf-8",
    "md": "text/markdown; charset=utf-8",
}

# Markdown in, no raw output-format code (sanitize.lua also drops raw blocks, images and metadata
# beyond title/author/date, so a report can never read files or inject Word, ODF or Typst code).
FROM = "markdown-raw_html-raw_attribute-raw_tex"

# Families Word and Pages know but the container has no licence for: the PDF uses a metric-compatible
# free font instead, so line breaks and page counts match the Word file.
PDF_FONTS = {
    "times new roman": "Liberation Serif",
    "times": "Liberation Serif",
    "georgia": "Liberation Serif",
    "garamond": "Liberation Serif",
    "cambria": "Liberation Serif",
    "arial": "Liberation Sans",
    "helvetica": "Liberation Sans",
    "calibri": "Liberation Sans",
    "verdana": "Liberation Sans",
    "courier new": "Liberation Mono",
    "courier": "Liberation Mono",
}

DEFAULT_STYLE = {
    "fontFamily": "Times New Roman",
    "fontSizePt": 12.0,
    "lineSpacing": 1.0,
    "marginIn": 1.0,
    "paragraphIndentIn": 0.0,
    "paragraphSpacingPt": 8.0,
    "headingFontFamily": "Libre Caslon Text",
    "headingSizesPt": [16.0, 14.0, 12.0],
    "headingBold": True,
    "headingItalic": False,
    "titleSizePt": 20.0,
    "titleBold": True,
    "titleItalic": True,
    "align": "left",
    "pageSize": "letter",
    "pageNumbers": False,
    "titlePage": False,
    "citationStyle": None,
}

FAMILY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 \-]{0,63}$")


class BadRequest(Exception):
    pass


def number(style, key, low, high):
    value = style[key]
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not (low <= value <= high):
        raise BadRequest(f"{key} must be a number from {low} to {high}")
    return float(value)


def normalize_style(raw):
    """The style with defaults filled in and every field checked (the same limits as ReportStyle)."""
    if raw is None:
        raw = {}
    if not isinstance(raw, dict):
        raise BadRequest("style must be an object")
    style = dict(DEFAULT_STYLE)
    style.update({k: v for k, v in raw.items() if k in DEFAULT_STYLE})
    if style["headingFontFamily"] is None:  # older backends: null meant the body's family
        style["headingFontFamily"] = style["fontFamily"]
    for key in ("fontFamily", "headingFontFamily"):
        value = style[key]
        if not isinstance(value, str) or not FAMILY.match(value):
            raise BadRequest(f"{key} must be a font name")
    style["fontSizePt"] = number(style, "fontSizePt", 6, 72)
    style["lineSpacing"] = number(style, "lineSpacing", 0.8, 3)
    style["marginIn"] = number(style, "marginIn", 0.25, 3)
    style["paragraphIndentIn"] = number(style, "paragraphIndentIn", 0, 2)
    style["paragraphSpacingPt"] = number(style, "paragraphSpacingPt", 0, 72)
    style["titleSizePt"] = number(style, "titleSizePt", 6, 96)
    sizes = style["headingSizesPt"]
    if not isinstance(sizes, list) or not (1 <= len(sizes) <= 6) or any(
        isinstance(s, bool) or not isinstance(s, (int, float)) or not (6 <= s <= 96) for s in sizes
    ):
        raise BadRequest("headingSizesPt must list 1 to 6 sizes from 6 to 96")
    style["headingSizesPt"] = [float(s) for s in sizes]
    if style["align"] not in ("left", "justify"):
        raise BadRequest("align must be left or justify")
    if style["pageSize"] not in ("letter", "a4"):
        raise BadRequest("pageSize must be letter or a4")
    for key in ("pageNumbers", "titlePage", "headingBold", "headingItalic", "titleBold", "titleItalic"):
        if not isinstance(style[key], bool):
            raise BadRequest(f"{key} must be true or false")
    return style


def heading_size(style, level):
    sizes = style["headingSizesPt"]
    return sizes[min(max(level - 1, 0), len(sizes) - 1)]


def heading_family(style):
    return style["headingFontFamily"] or style["fontFamily"]


def heading_face(style, title=False):
    """(bold, italic) of the title or the headings."""
    return (style["titleBold"], style["titleItalic"]) if title else (style["headingBold"], style["headingItalic"])


def fmt(value):
    """A number for XML or Typst: no trailing .0, at most three decimals."""
    text = f"{value:.3f}".rstrip("0").rstrip(".")
    return text or "0"


# ---- Word (reference.docx) ------------------------------------------------------------------------

STYLE_BLOCK = re.compile(r"<w:style\b[^>]*>.*?</w:style>", re.S)
STYLE_ID = re.compile(r'w:styleId="([^"]+)"')
RFONTS = re.compile(r"<w:rFonts\b[^>]*/>")
COLOR = re.compile(r"<w:color\b[^>]*/>")
SZ = re.compile(r'<w:sz\s+w:val="[^"]*"\s*/>')
SZCS = re.compile(r'<w:szCs\s+w:val="[^"]*"\s*/>')
PPR = re.compile(r"<w:pPr\b[^>]*/>|<w:pPr\b[^>]*>.*?</w:pPr>", re.S)
RPR_OPEN = re.compile(r"<w:rPr\b[^>]*>")
HEADING = re.compile(r"^Heading(\d)(Char)?$")
KEEP_FONTS = {"VerbatimChar", "SourceCode"}


def rfonts(family):
    return f'<w:rFonts w:ascii="{family}" w:hAnsi="{family}" w:eastAsia="{family}" w:cs="{family}" />'


def set_size(block, points):
    half = str(int(round(points * 2)))
    if SZ.search(block):
        block = SZ.sub(f'<w:sz w:val="{half}" />', block, count=1)
        if SZCS.search(block):
            return SZCS.sub(f'<w:szCs w:val="{half}" />', block, count=1)
        return block.replace(f'<w:sz w:val="{half}" />', f'<w:sz w:val="{half}" /><w:szCs w:val="{half}" />', 1)
    sizes = f'<w:sz w:val="{half}" /><w:szCs w:val="{half}" />'
    if "</w:rPr>" in block:
        return block.replace("</w:rPr>", sizes + "</w:rPr>", 1)
    return block.replace("</w:style>", f"<w:rPr>{sizes}</w:rPr></w:style>", 1)


def add_rfonts(block, family):
    match = RPR_OPEN.search(block)
    if match:
        return block[: match.end()] + rfonts(family) + block[match.end():]
    return block.replace("</w:style>", f"<w:rPr>{rfonts(family)}</w:rPr></w:style>", 1)


FACE = re.compile(r"<w:(?:b|bCs|i|iCs)\b[^>]*/>")


def set_face(block, bold, italic):
    """Bold and italic set explicitly on a style (pandoc's defaults differ by heading level)."""
    block = FACE.sub("", block)
    face = ('<w:b /><w:bCs />' if bold else '<w:b w:val="0" /><w:bCs w:val="0" />') + \
           ('<w:i /><w:iCs />' if italic else '<w:i w:val="0" /><w:iCs w:val="0" />')
    match = RPR_OPEN.search(block)
    if not match:
        return block.replace("</w:style>", f"<w:rPr>{face}</w:rPr></w:style>", 1)
    fonts = RFONTS.search(block, match.end())
    at = fonts.end() if fonts and fonts.start() == match.end() else match.end()
    return block[:at] + face + block[at:]


def set_ppr(block, ppr):
    if PPR.search(block):
        return PPR.sub(lambda _: ppr, block, count=1)
    if RPR_OPEN.search(block):
        match = RPR_OPEN.search(block)
        return block[: match.start()] + ppr + block[match.start():]
    return block.replace("</w:style>", ppr + "</w:style>", 1)


def patch_docx_styles(xml, style):
    family = style["fontFamily"]
    body_half = int(round(style["fontSizePt"] * 2))
    line = int(round(240 * style["lineSpacing"]))
    after = int(round(style["paragraphSpacingPt"] * 20))
    indent = int(round(style["paragraphIndentIn"] * 1440))
    justify = '<w:jc w:val="both" />' if style["align"] == "justify" else ""
    head_family = heading_family(style)

    def patch(match):
        block = match.group(0)
        found = STYLE_ID.search(block)
        sid = found.group(1) if found else ""
        if sid in KEEP_FONTS or sid.endswith("Tok"):
            return block
        block = RFONTS.sub("", block)
        heading = HEADING.match(sid)
        if heading:
            block = COLOR.sub("", set_size(block, heading_size(style, int(heading.group(1)))))
            if head_family != family:
                block = add_rfonts(block, head_family)
            block = set_face(block, *heading_face(style))
        elif sid in ("Title", "TitleChar"):
            block = COLOR.sub("", set_size(block, style["titleSizePt"]))
            if head_family != family:
                block = add_rfonts(block, head_family)
            block = set_face(block, *heading_face(style, title=True))
        elif sid in ("Subtitle", "SubtitleChar"):
            block = COLOR.sub("", block)
        elif sid in ("Author", "Date"):
            # Based on Title: back to the body's family and face.
            block = set_face(add_rfonts(set_size(block, style["fontSizePt"]), family), False, False)
        elif sid == "BodyText":
            block = set_ppr(
                block,
                f'<w:pPr><w:spacing w:before="0" w:after="{after}" w:line="{line}" w:lineRule="auto" />'
                f'<w:ind w:firstLine="{indent}" />{justify}</w:pPr>',
            )
        elif sid == "BlockText":
            # Quotes as the app shows them: indented, with a thin rule on the left.
            block = set_ppr(
                block,
                '<w:pPr><w:pBdr><w:left w:val="single" w:sz="6" w:space="10" w:color="A6A6A6" /></w:pBdr>'
                '<w:spacing w:before="120" w:after="120" /><w:ind w:firstLine="0" w:left="480" w:right="480" /></w:pPr>',
            )
        elif sid == "Compact":
            block = set_ppr(block, '<w:pPr><w:spacing w:before="36" w:after="36" /><w:ind w:firstLine="0" /></w:pPr>')
        return block

    xml = STYLE_BLOCK.sub(patch, xml)
    xml = re.sub(
        r"<w:rPrDefault>.*?</w:rPrDefault>",
        lambda _: f'<w:rPrDefault><w:rPr>{rfonts(family)}<w:sz w:val="{body_half}" /><w:szCs w:val="{body_half}" />'
        '<w:lang w:val="en-US" w:eastAsia="en-US" w:bidi="ar-SA" /></w:rPr></w:rPrDefault>',
        xml,
        count=1,
        flags=re.S,
    )
    xml = re.sub(
        r"<w:pPrDefault>.*?</w:pPrDefault>",
        lambda _: f'<w:pPrDefault><w:pPr><w:spacing w:after="{after}" w:line="{line}" w:lineRule="auto" /></w:pPr></w:pPrDefault>',
        xml,
        count=1,
        flags=re.S,
    )
    return xml


SECT = re.compile(r"<w:sectPr\b[^>]*/>|<w:sectPr\b[^>]*>.*?</w:sectPr>", re.S)
FOOTNOTE_PR = re.compile(r"<w:footnotePr\b[^>]*/>|<w:footnotePr\b[^>]*>.*?</w:footnotePr>", re.S)


def patch_docx_document(xml, style):
    margin = int(round(style["marginIn"] * 1440))
    width, height = (12240, 15840) if style["pageSize"] == "letter" else (11906, 16838)

    def section(old):
        footnotes = FOOTNOTE_PR.search(old)
        footer = f'<w:footerReference w:type="default" r:id="{FOOTER_RID}" />' if style["pageNumbers"] else ""
        return (
            "<w:sectPr>" + footer + (footnotes.group(0) if footnotes else "")
            + f'<w:pgSz w:w="{width}" w:h="{height}" />'
            + f'<w:pgMar w:top="{margin}" w:right="{margin}" w:bottom="{margin}" w:left="{margin}" w:header="720" w:footer="720" w:gutter="0" />'
            + "</w:sectPr>"
        )

    if style["pageNumbers"] and "xmlns:r=" not in xml[: xml.find(">", xml.find("<w:document"))]:
        xml = xml.replace("<w:document", f'<w:document xmlns:r="{R_NS}"', 1)
    matches = list(SECT.finditer(xml))
    if matches:
        last = matches[-1]
        return xml[: last.start()] + section(last.group(0)) + xml[last.end():]
    return xml.replace("</w:body>", section("") + "</w:body>", 1)


W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
FOOTER_RID = "rIdClarityFooter"
FOOTER_PART = "word/footer-clarity.xml"
FOOTER_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"
FOOTER_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"


def docx_footer(style):
    """A footer with the page number (a PAGE field), centred like the PDF's, in the body font and size."""
    half = int(round(style["fontSizePt"] * 2))
    run_pr = f'<w:rPr>{rfonts(style["fontFamily"])}<w:sz w:val="{half}" /><w:szCs w:val="{half}" /></w:rPr>'
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        f'<w:ftr xmlns:w="{W_NS}" xmlns:r="{R_NS}"><w:p><w:pPr><w:jc w:val="center" /></w:pPr>'
        f'<w:r>{run_pr}<w:fldChar w:fldCharType="begin" /></w:r>'
        f'<w:r>{run_pr}<w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>'
        f'<w:r>{run_pr}<w:fldChar w:fldCharType="separate" /></w:r>'
        f'<w:r>{run_pr}<w:t>1</w:t></w:r>'
        f'<w:r>{run_pr}<w:fldChar w:fldCharType="end" /></w:r>'
        "</w:p></w:ftr>"
    )


FONT_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/font"
OBFUSCATED_FONT = "application/vnd.openxmlformats-officedocument.obfuscatedFont"
# Where each byte of the key sits in "{XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}" (the GUID's bytes reversed).
FONT_KEY_POSITIONS = (35, 33, 31, 29, 27, 25, 22, 20, 17, 15, 12, 10, 7, 5, 3, 1)
DOCX_FACES = {("normal", "normal"): "embedRegular", ("normal", "bold"): "embedBold",
              ("italic", "normal"): "embedItalic", ("italic", "bold"): "embedBoldItalic"}


def font_key(name):
    """A stable GUID per embedded file, in Word's braces form."""
    return "{" + str(uuid.uuid5(uuid.NAMESPACE_URL, "clarity-render-font:" + name)).upper() + "}"


def obfuscate_font(data, key):
    """Word's font obfuscation (ECMA-376 part 2): the first 32 bytes XORed with the key's bytes."""
    xor = bytes(int(key[p:p + 2], 16) for p in FONT_KEY_POSITIONS)
    out = bytearray(data)
    for i in range(min(32, len(out))):
        out[i] ^= xor[i % 16]
    return bytes(out)


def docx_fonts(style):
    """The heading family's faces this image can embed: (font-table entry, rels, parts by name)."""
    family = heading_family(style)
    files = embedded_files(family) if family != style["fontFamily"] else []
    embeds, rels, parts = "", "", {}
    for i, (name, path, font_style, weight) in enumerate(files, 1):
        key = font_key(name)
        part = f"fonts/font{i}.odttf"
        with open(path, "rb") as f:
            parts["word/" + part] = obfuscate_font(f.read(), key)
        rels += f'<Relationship Id="rIdClarityFont{i}" Type="{FONT_REL}" Target="{part}" />'
        embeds += f'<w:{DOCX_FACES[(font_style, weight)]} r:id="rIdClarityFont{i}" w:fontKey="{key}" />'
    return embeds, rels, parts


def patch_font_table(xml, style, embeds=""):
    """Names the heading family in the font table, with the body's family (Times New Roman) as its
    stand-in, and the faces embedded in the file: Word uses those where the font is not installed."""
    family = heading_family(style)
    if family == style["fontFamily"] or f'w:name="{family}"' in xml:
        return xml
    entry = (f'<w:font w:name="{family}"><w:altName w:val="{style["fontFamily"]}" /><w:charset w:val="00" />'
             f'<w:family w:val="roman" /><w:pitch w:val="variable" />{embeds}</w:font>')
    if "xmlns:r=" not in xml[: xml.find(">", xml.find("<w:fonts"))]:
        xml = xml.replace("<w:fonts", f'<w:fonts xmlns:r="{R_NS}"', 1)
    return xml.replace("</w:fonts>", entry + "</w:fonts>", 1)


def add_footer_rel(xml):
    rel = f'<Relationship Id="{FOOTER_RID}" Type="{FOOTER_REL}" Target="{FOOTER_PART.split("/", 1)[1]}" />'
    return xml.replace("</Relationships>", rel + "</Relationships>", 1)


def add_odttf_type(xml):
    if 'Extension="odttf"' in xml:
        return xml
    return xml.replace("</Types>", f'<Default Extension="odttf" ContentType="{OBFUSCATED_FONT}" /></Types>', 1)


def add_footer_type(xml):
    override = f'<Override PartName="/{FOOTER_PART}" ContentType="{FOOTER_TYPE}" />'
    return xml.replace("</Types>", override + "</Types>", 1)


# ---- OpenDocument (reference.odt) -----------------------------------------------------------------

ODF_STYLE = re.compile(r"<style:style\b[^>]*?/>|<style:style\b[^>]*>.*?</style:style>", re.S)
ODF_NAME = re.compile(r'style:name="([^"]+)"')
ODF_PARENT = re.compile(r'style:parent-style-name="([^"]+)"')
ODF_HEADING = re.compile(r"^Heading_20_(\d)$")


def odf_attr(text, name, value):
    """Sets attribute [name] wherever it appears in [text]."""
    return re.sub(rf'{re.escape(name)}="[^"]*"', f'{name}="{value}"', text)


# Fonts the image has that OpenDocument files carry inside them (the reader's computer rarely has
# them): family (lower case) -> (file under FONT_DIR, the face's style, weight).
FONT_DIR = os.environ.get("RENDER_FONT_DIR", "/usr/share/fonts/clarity")
EMBEDDED = {
    "libre caslon text": [
        ("LibreCaslonText-Regular.ttf", "normal", "normal"),
        ("LibreCaslonText-Bold.ttf", "normal", "bold"),
        ("LibreCaslonText-Italic.ttf", "italic", "normal"),
        ("LibreCaslonText-BoldItalic.ttf", "italic", "bold"),
    ],
}
XLINK_NS = "http://www.w3.org/1999/xlink"
LOEXT_NS = "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0"


def embedded_files(family):
    """(name in the package, path, style, weight) of the faces of [family] this image can embed."""
    return [
        (f"Fonts/{name}", os.path.join(FONT_DIR, name), font_style, weight)
        for name, font_style, weight in EMBEDDED.get(family.lower(), [])
        if os.path.isfile(os.path.join(FONT_DIR, name))
    ]


def odf_font_face(family, generic="roman"):
    files = embedded_files(family)
    head = f'<style:font-face style:name="{family}" svg:font-family="\'{family}\'" style:font-family-generic="{generic}" style:font-pitch="variable"'
    if not files:
        return head + " />"
    uris = "".join(
        f'<svg:font-face-uri xlink:href="{name}" xlink:type="simple" loext:font-style="{font_style}" loext:font-weight="{weight}">'
        '<svg:font-face-format svg:string="truetype" /></svg:font-face-uri>'
        for name, _, font_style, weight in files
    )
    return head + f"><svg:font-face-src>{uris}</svg:font-face-src></style:font-face>"


def odf_namespaces(xml):
    """Declares xlink and loext on the root when the embedded fonts need them."""
    root_end = xml.find(">", xml.find("<office:document-styles"))
    root = xml[:root_end]
    extra = ""
    if "xmlns:xlink=" not in root:
        extra += f' xmlns:xlink="{XLINK_NS}"'
    if "xmlns:loext=" not in root:
        extra += f' xmlns:loext="{LOEXT_NS}"'
    return xml[:root_end] + extra + xml[root_end:]


def odf_sizes(block, points):
    size = f"{fmt(points)}pt"
    for attr in ("fo:font-size", "style:font-size-asian", "style:font-size-complex"):
        block = odf_attr(block, attr, size)
    if "fo:font-size=" not in block:
        if "<style:text-properties" in block:
            block = block.replace("<style:text-properties", f'<style:text-properties fo:font-size="{size}"', 1)
        elif block.endswith("/>"):
            block = block[:-2].rstrip() + f'><style:text-properties fo:font-size="{size}" /></style:style>'
        else:
            block = block.replace("</style:style>", f'<style:text-properties fo:font-size="{size}" /></style:style>', 1)
    return block


def odf_font(block, family):
    if 'style:font-name="' in block:
        return odf_attr(block, "style:font-name", family)
    if "<style:text-properties" in block:
        return block.replace("<style:text-properties", f'<style:text-properties style:font-name="{family}"', 1)
    return block


def odf_text_attr(block, name, value):
    """Sets [name] on the style's text properties, adding them when it has none."""
    if f'{name}="' in block:
        return odf_attr(block, name, value)
    if "<style:text-properties" in block:
        return block.replace("<style:text-properties", f'<style:text-properties {name}="{value}"', 1)
    if block.endswith("/>"):
        return block[:-2].rstrip() + f'><style:text-properties {name}="{value}" /></style:style>'
    return block.replace("</style:style>", f'<style:text-properties {name}="{value}" /></style:style>', 1)


def odf_paragraph_attr(block, name, value):
    """Sets [name] on the style's paragraph properties, adding them when it has none."""
    if f'{name}="' in block:
        return odf_attr(block, name, value)
    if "<style:paragraph-properties" in block:
        return block.replace("<style:paragraph-properties", f'<style:paragraph-properties {name}="{value}"', 1)
    if block.endswith("/>"):
        return block[:-2].rstrip() + f'><style:paragraph-properties {name}="{value}" /></style:style>'
    opening = block.index(">") + 1
    return block[:opening] + f'<style:paragraph-properties {name}="{value}" />' + block[opening:]


def odf_face(block, bold, italic):
    for attr in ("fo:font-weight", "style:font-weight-asian", "style:font-weight-complex"):
        block = odf_text_attr(block, attr, "bold" if bold else "normal")
    for attr in ("fo:font-style", "style:font-style-asian", "style:font-style-complex"):
        block = odf_text_attr(block, attr, "italic" if italic else "normal")
    return block


def odf_no_indent(block):
    if "fo:text-indent=" in block:
        return odf_attr(block, "fo:text-indent", "0in")
    if "<style:paragraph-properties" in block:
        return block.replace("<style:paragraph-properties", '<style:paragraph-properties fo:text-indent="0in"', 1)
    if block.endswith("/>"):
        return block[:-2].rstrip() + '><style:paragraph-properties fo:text-indent="0in" /></style:style>'
    opening = block.index(">") + 1
    return block[:opening] + '<style:paragraph-properties fo:text-indent="0in" />' + block[opening:]


def patch_odt_styles(xml, style):
    family = style["fontFamily"]
    head_family = heading_family(style)
    body = f"{fmt(style['fontSizePt'])}pt"
    after = f"{fmt(style['paragraphSpacingPt'] / 72)}in"
    line = f"{fmt(style['lineSpacing'] * 100)}%"
    indent = f"{fmt(style['paragraphIndentIn'])}in"
    align = "justify" if style["align"] == "justify" else "start"
    margin = f"{fmt(style['marginIn'])}in"
    width, height = ("8.5in", "11in") if style["pageSize"] == "letter" else ("8.2681in", "11.6929in")

    faces = "".join(odf_font_face(f) for f in dict.fromkeys([family, head_family]) if f'style:name="{f}"' not in xml)
    xml = xml.replace("<office:font-face-decls>", "<office:font-face-decls>" + faces, 1)
    if embedded_files(head_family) or embedded_files(family):
        xml = odf_namespaces(xml)

    def default_paragraph(match):
        block = match.group(0)
        block = odf_attr(block, "style:font-name", family)
        for attr in ("fo:font-size", "style:font-size-asian", "style:font-size-complex"):
            block = odf_attr(block, attr, body)
        return block

    xml = re.sub(r'<style:default-style style:family="paragraph">.*?</style:default-style>', default_paragraph, xml, count=1, flags=re.S)

    def patch(match):
        block = match.group(0)
        found = ODF_NAME.search(block)
        name = found.group(1) if found else ""
        parent = ODF_PARENT.search(block)
        heading = ODF_HEADING.match(name)
        if name == "Heading":
            block = odf_face(odf_sizes(odf_text_attr(odf_font(block, head_family), "style:font-name", head_family), heading_size(style, 1)), *heading_face(style))
        elif heading:
            block = odf_face(odf_sizes(odf_text_attr(block, "style:font-name", head_family), heading_size(style, int(heading.group(1)))), *heading_face(style))
        elif name == "Title":
            block = odf_face(odf_sizes(odf_text_attr(odf_font(block, head_family), "style:font-name", head_family), style["titleSizePt"]), *heading_face(style, title=True))
        elif name == "Text_20_body":
            props = (
                f'<style:paragraph-properties fo:margin-top="0in" fo:margin-bottom="{after}" fo:line-height="{line}" '
                f'fo:text-indent="{indent}" fo:text-align="{align}" style:contextual-spacing="false" />'
            )
            if "<style:paragraph-properties" in block:
                block = re.sub(r"<style:paragraph-properties\b[^>]*?/>|<style:paragraph-properties\b[^>]*>.*?</style:paragraph-properties>",
                               lambda _: props, block, count=1, flags=re.S)
            elif block.endswith("/>"):
                block = block[:-2].rstrip() + ">" + props + "</style:style>"
            else:
                opening = block.index(">") + 1
                block = block[:opening] + props + block[opening:]
        elif name == "Quotations":
            # Quotes as the app shows them: indented, with a thin rule on the left.
            for attr, value in (("fo:margin-left", "0.33in"), ("fo:padding-left", "0.14in"), ("fo:border-left", "0.5pt solid #a6a6a6"),
                                ("fo:border-right", "none"), ("fo:border-top", "none"), ("fo:border-bottom", "none")):
                block = odf_paragraph_attr(block, attr, value)
        elif parent and parent.group(1) == "Text_20_body" and name != "First_20_paragraph":
            # Lists, tables and quotes hang off the body style: they keep no first-line indent.
            block = odf_no_indent(block)
        return block

    xml = ODF_STYLE.sub(patch, xml)

    def page(match):
        block = match.group(0)
        for attr in ("fo:margin-top", "fo:margin-bottom", "fo:margin-left", "fo:margin-right"):
            block = odf_attr(block, attr, margin)
        block = odf_attr(block, "fo:page-width", width)
        return odf_attr(block, "fo:page-height", height)

    xml = re.sub(r"<style:page-layout-properties\b[^>]*>", page, xml)
    if style["pageNumbers"]:
        xml = odt_page_numbers(xml, style)
    return xml


ODF_FOOTER_STYLE = (
    '<style:footer-style><style:header-footer-properties fo:min-height="0in" fo:margin-top="0.1in" />'
    "</style:footer-style>"
)


def odt_page_numbers(xml, style):
    """A centred page number in the footer of every master page, the body font and size."""
    family = style["fontFamily"]
    size = f"{fmt(style['fontSizePt'])}pt"
    paragraph_style = (
        '<style:style style:name="Clarity_Page_Number" style:family="paragraph">'
        '<style:paragraph-properties fo:text-align="center" fo:margin-top="0in" fo:margin-bottom="0in" />'
        f'<style:text-properties style:font-name="{family}" fo:font-size="{size}" /></style:style>'
    )
    xml = xml.replace("</office:styles>", paragraph_style + "</office:styles>", 1)
    footer = (
        '<style:footer><text:p text:style-name="Clarity_Page_Number">'
        '<text:page-number text:select-page="current">1</text:page-number></text:p></style:footer>'
    )

    def layout(match):
        block = match.group(0)
        if "<style:footer-style" in block:
            return block
        if block.endswith("/>"):
            return block[:-2].rstrip() + ">" + ODF_FOOTER_STYLE + "</style:page-layout>"
        return block.replace("</style:page-layout>", ODF_FOOTER_STYLE + "</style:page-layout>", 1)

    xml = re.sub(r"<style:page-layout\b[^>]*?/>|<style:page-layout\b[^>]*>.*?</style:page-layout>", layout, xml, flags=re.S)

    def master(match):
        block = match.group(0)
        if "<style:footer>" in block or "<style:footer " in block:
            return block
        if block.endswith("/>"):
            return block[:-2].rstrip() + ">" + footer + "</style:master-page>"
        return block.replace("</style:master-page>", footer + "</style:master-page>", 1)

    return re.sub(r"<style:master-page\b[^>]*?/>|<style:master-page\b[^>]*>.*?</style:master-page>", master, xml, flags=re.S)


# ---- reference documents --------------------------------------------------------------------------

def patched_zip(path, patches, extra=None):
    """A copy of the zip at [path] with [patches] (name -> fn(text)) applied and [extra] (name -> text)
    added at the end; fixed timestamps, same order."""
    out = io.BytesIO()

    def write(target, name, data):
        entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        # ODF wants `mimetype` first and stored; everything else deflated.
        entry.compress_type = zipfile.ZIP_STORED if name == "mimetype" else zipfile.ZIP_DEFLATED
        target.writestr(entry, data)

    with zipfile.ZipFile(path) as source, zipfile.ZipFile(out, "w") as target:
        for info in source.infolist():
            data = source.read(info.filename)
            if info.filename in patches:
                data = patches[info.filename](data.decode("utf-8")).encode("utf-8")
            write(target, info.filename, data)
        for name, data in (extra or {}).items():
            write(target, name, data if isinstance(data, bytes) else data.encode("utf-8"))
    return out.getvalue()


def reference_docx(style):
    patches = {
        "word/styles.xml": lambda xml: patch_docx_styles(xml, style),
        "word/document.xml": lambda xml: patch_docx_document(xml, style),
    }
    embeds, font_rels, font_parts = docx_fonts(style)
    patches["word/fontTable.xml"] = lambda xml: patch_font_table(xml, style, embeds)
    extra = dict(font_parts)
    if font_parts:
        patches["[Content_Types].xml"] = lambda xml: add_odttf_type(xml)
        extra["word/_rels/fontTable.xml.rels"] = (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{font_rels}</Relationships>'
        )
    if style["pageNumbers"]:
        # pandoc keeps the reference document's headers and footers (and the sectPr that names them).
        patches["word/_rels/document.xml.rels"] = add_footer_rel
        types = patches.get("[Content_Types].xml", lambda xml: xml)
        patches["[Content_Types].xml"] = lambda xml: add_footer_type(types(xml))
        extra[FOOTER_PART] = docx_footer(style)
    return patched_zip(os.path.join(DATA, "reference.docx"), patches, extra)


def reference_odt(style):
    files = {}
    for family in dict.fromkeys([style["fontFamily"], heading_family(style)]):
        for name, path, _, _ in embedded_files(family):
            files[name] = path
    patches = {"styles.xml": lambda xml: patch_odt_styles(xml, style)}
    if files:
        patches["META-INF/manifest.xml"] = lambda xml: xml.replace(
            "</manifest:manifest>",
            "".join(f'<manifest:file-entry manifest:full-path="{n}" manifest:media-type="application/x-font-ttf" />' for n in files)
            + "</manifest:manifest>",
            1,
        )
    extra = {}
    for name, path in files.items():
        with open(path, "rb") as f:
            extra[name] = f.read()
    return patched_zip(os.path.join(DATA, "reference.odt"), patches, extra)


# ---- PDF (Typst) ------------------------------------------------------------------------------------

def pdf_family(name):
    return PDF_FONTS.get(name.lower(), name)


def typst_string(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def typst_fonts(name):
    fonts = [pdf_family(name)]
    if fonts[0] != "Liberation Serif":
        fonts.append("Liberation Serif")
    return "(" + ", ".join(typst_string(f) for f in fonts) + ",)"


def typst_style(style):
    """Typst rules that run inside pandoc's template (after its own), so they win."""
    spacing = style["lineSpacing"]
    # Word's line pitch is about 1.15 em per spacing unit; Typst's is the line box (~0.7 em) plus leading.
    leading = max(0.3, 1.15 * spacing - 0.7)
    gap = f"{fmt(leading)}em + {fmt(style['paragraphSpacingPt'])}pt"
    indent = style["paragraphIndentIn"]
    first = f"(amount: {fmt(indent)}in, all: true)" if indent > 0 else "0pt"
    lines = [
        f"#set text(font: {typst_fonts(style['fontFamily'])}, size: {fmt(style['fontSizePt'])}pt)",
        f"#set par(justify: {'true' if style['align'] == 'justify' else 'false'}, leading: {fmt(leading)}em, "
        f"spacing: {gap}, first-line-indent: {first})",
    ]
    bold, italic = heading_face(style)
    lines.append(
        f"#show heading: set text(font: {typst_fonts(heading_family(style))}, weight: \"{'bold' if bold else 'regular'}\", "
        f"style: \"{'italic' if italic else 'normal'}\")"
    )
    lines.append("#show heading: set block(above: 1.6em, below: 0.9em)")
    # Quotes as the app shows them: indented, with a thin rule on the left.
    lines.append(
        "#show quote.where(block: true): it => block(inset: (left: 12pt, y: 2pt), outset: (y: 2pt), "
        "stroke: (left: 0.75pt + luma(166)), above: 1.1em, below: 1.1em, it.body)"
    )
    for level in range(1, 7):
        lines.append(f"#show heading.where(level: {level}): set text(size: {fmt(heading_size(style, level))}pt)")
    return "\n".join(lines) + "\n"


def typst_defaults(style):
    """pandoc defaults (JSON is YAML): the template variables for page, margins and the title's font."""
    variables = {
        "mainfont": pdf_family(style["fontFamily"]),
        "fontsize": f"{fmt(style['fontSizePt'])}pt",
        "papersize": "us-letter" if style["pageSize"] == "letter" else "a4",
        "margin": {"x": f"{fmt(style['marginIn'])}in", "y": f"{fmt(style['marginIn'])}in"},
    }
    if style["pageNumbers"]:
        variables["page-numbering"] = "1"
    bold, italic = heading_face(style, title=True)
    # Our template (clarity.typst) draws the title with these; template variables are not escaped.
    variables["clarity-title-style"] = (
        f"(font: {typst_fonts(heading_family(style))}, size: {fmt(style['titleSizePt'])}pt, "
        f"weight: \"{'bold' if bold else 'regular'}\", style: \"{'italic' if italic else 'normal'}\")"
    )
    return json.dumps({"variables": variables})


# ---- rendering --------------------------------------------------------------------------------------

def pandoc_command(fmt_name, work, style):
    """The pandoc command line for [fmt_name], with its side files written to [work]. Pure apart from the files."""
    source = os.path.join(work, "source.md")
    # --sandbox: pandoc itself reads nothing but the files named here (sanitize.lua already drops what
    # could name another one; this is the second lock).
    command = [PANDOC, source, "--sandbox", "--from", FROM, "--lua-filter", os.path.join(DATA, "sanitize.lua")]
    if style["titlePage"]:
        command += ["--lua-filter", os.path.join(DATA, "titlepage.lua")]
    if fmt_name == "txt":
        out = os.path.join(work, "out.txt")
        command += ["--to", "plain", "--standalone", "--wrap=auto", "--columns=80"]
    elif fmt_name == "docx":
        out = os.path.join(work, "out.docx")
        ref = os.path.join(work, "reference.docx")
        with open(ref, "wb") as f:
            f.write(reference_docx(style))
        command += ["--to", "docx", "--reference-doc", ref]
    elif fmt_name == "odt":
        out = os.path.join(work, "out.odt")
        ref = os.path.join(work, "reference.odt")
        with open(ref, "wb") as f:
            f.write(reference_odt(style))
        command += ["--to", "odt", "--reference-doc", ref]
    elif fmt_name == "pdf":
        out = os.path.join(work, "out.pdf")
        defaults = os.path.join(work, "defaults.yaml")
        rules = os.path.join(work, "style.typ")
        with open(defaults, "w", encoding="utf-8") as f:
            f.write(typst_defaults(style))
        with open(rules, "w", encoding="utf-8") as f:
            f.write(typst_style(style))
        command += ["--to", "typst", "--pdf-engine", "typst", "--template", os.path.join(DATA, "clarity.typst"),
                    "--defaults", defaults, "--include-before-body", rules, "--standalone"]
    else:
        raise BadRequest(f"unknown format {fmt_name}")
    return command + ["--output", out], out


def run(command, cwd):
    """Runs [command] in its own process group (pandoc starts typst), killed whole on timeout."""
    env = {
        "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
        "HOME": cwd,
        "TMPDIR": cwd,
        "LANG": "C.UTF-8",
        "TYPST_FONT_PATHS": os.environ.get("TYPST_FONT_PATHS", "/usr/share/fonts"),
    }
    process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, start_new_session=True)
    try:
        _, err = process.communicate(timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate()
        raise TimeoutError()
    return process.returncode, err.decode("utf-8", "replace")


def render(source, style, fmt_name):
    if fmt_name == "md":
        return source.encode("utf-8")
    work = tempfile.mkdtemp(prefix="render-")
    try:
        with open(os.path.join(work, "source.md"), "w", encoding="utf-8") as f:
            f.write(source)
        command, out = pandoc_command(fmt_name, work, style)
        code, err = run(command, work)
        if code != 0 or not os.path.isfile(out):
            raise RenderFailed(last_lines(err))
        with open(out, "rb") as f:
            return f.read()
    finally:
        shutil.rmtree(work, ignore_errors=True)


class RenderFailed(Exception):
    pass


def last_lines(text, limit=600):
    text = text.strip()
    return text[-limit:] if text else "pandoc failed without a message"


def tool_version(command):
    try:
        out = subprocess.run(command, capture_output=True, timeout=20, check=False).stdout.decode("utf-8", "replace")
        return out.strip().splitlines()[0] if out.strip() else "unknown"
    except (OSError, subprocess.TimeoutExpired):
        return "missing"


def own_digest():
    digest = hashlib.sha256()
    for name in ("renderd.py", "sanitize.lua", "titlepage.lua", "reference.docx", "reference.odt"):
        path = os.path.join(DATA, name) if name.startswith("reference") else os.path.join(HERE, name)
        try:
            with open(path, "rb") as f:
                digest.update(f.read())
        except OSError:
            digest.update(b"-")
    return digest.hexdigest()[:12]


def read_key():
    try:
        with open(KEY_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


# ---- HTTP -------------------------------------------------------------------------------------------

VERSION = ""
SLOTS = threading.BoundedSemaphore(MAX_PARALLEL)


class Handler(BaseHTTPRequestHandler):
    server_version = "renderd"
    sys_version = ""

    def log_message(self, format, *args):  # one short line per request, never the body
        sys.stderr.write("%s %s\n" % (self.command, format % args))

    def answer(self, status, body, content_type="application/json", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Render-Version", VERSION)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def error(self, status, message):
        self.answer(status, json.dumps({"error": message}).encode("utf-8"))

    def do_GET(self):
        if self.path == "/healthz":
            self.answer(200, json.dumps({"status": "ok", "version": VERSION}).encode("utf-8"))
        else:
            self.error(404, "not found")

    def do_POST(self):
        if self.path != "/render":
            return self.error(404, "not found")
        key = read_key()
        given = self.headers.get("Authorization", "")
        if not key or not hmac.compare_digest(given.encode("utf-8"), f"Bearer {key}".encode("utf-8")):
            return self.error(401, "a valid bearer key is required")
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self.error(413 if length > MAX_BODY else 400, f"the body must be 1 to {MAX_BODY} bytes")
        try:
            request = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(request, dict):
                raise BadRequest("the body must be a JSON object")
            source = request.get("source")
            fmt_name = request.get("format")
            if not isinstance(source, str) or not source.strip():
                raise BadRequest("source must be non-empty Markdown")
            if fmt_name not in FORMATS:
                raise BadRequest("format must be one of " + ", ".join(FORMATS))
            style = normalize_style(request.get("style"))
        except (BadRequest, ValueError) as e:
            return self.error(400, str(e))
        if not SLOTS.acquire(timeout=TIMEOUT):
            return self.error(503, "busy, try again")
        try:
            data = render(source, style, fmt_name)
        except TimeoutError:
            return self.error(504, f"rendering took longer than {fmt(TIMEOUT)} s")
        except RenderFailed as e:
            return self.error(422, str(e))
        except BadRequest as e:
            return self.error(400, str(e))
        finally:
            SLOTS.release()
        self.answer(200, data, FORMATS[fmt_name])


def main():
    global VERSION
    VERSION = "pandoc {}; {}; renderd {}".format(
        tool_version([PANDOC, "--version"]).replace("pandoc ", ""), tool_version(["typst", "--version"]), own_digest()
    )
    if not read_key():
        sys.stderr.write(f"renderd: no key in {KEY_FILE}; every render is refused\n")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    sys.stderr.write(f"renderd {VERSION} on :{PORT}\n")
    # As PID 1 nothing handles SIGTERM by default: stop serving when the container is stopped.
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    server.serve_forever()
    server.server_close()


if __name__ == "__main__":
    main()
