#!/usr/bin/env python3
"""Image part of the render smoke test (computers/render/smoke.sh runs it): sends a revision whose
Markdown names five images, supplies one of them, and checks what every format made of it.

    smoke_images.py <base url> <bearer key>

The supplied image must embed in pdf, docx and odt; an unsupplied URL, a file: URL, a plain path and a
data URI must each be just their caption; the image the request names must not leak anything. Standard
library only."""

import base64
import hashlib
import json
import re
import struct
import sys
import urllib.error
import urllib.request
import zipfile
import io
import zlib

BASE, KEY = sys.argv[1].rstrip("/"), sys.argv[2]
URL = "https://example.com/hare.png"
SOURCE = (
    "# Pictures\n\n"
    f"![Hare caption]({URL})\n\n"
    "![Unfetched caption](https://example.com/other.png)\n\n"
    "![Filefile caption](file:///etc/passwd)\n\n"
    "![Pathpath caption](/etc/passwd)\n\n"
    "![Datadata caption](data:image/png;base64,iVBORw0KGgo=)\n"
)


def png(width, height):
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))
    row = b"\x00" + bytes([200, 30, 30]) * width
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height)) + chunk(b"IEND", b""))


def post(fmt, images, source=SOURCE):
    body = {"source": source, "style": {}, "format": fmt}
    if images is not None:
        body["images"] = images
    request = urllib.request.Request(
        BASE + "/render", data=json.dumps(body).encode(), method="POST",
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def fail(message):
    print("FAIL: " + message)
    sys.exit(1)


def zip_names(data):
    return zipfile.ZipFile(io.BytesIO(data)).namelist()


def zip_text(data, name):
    return zipfile.ZipFile(io.BytesIO(data)).read(name).decode("utf-8", "replace")


def pdf_images(data):
    return len(re.findall(rb"/Subtype\s*/Image", data))


CAPTIONS = ("Unfetched caption", "Filefile caption", "Pathpath caption", "Datadata caption")
key = hashlib.sha256(URL.encode()).hexdigest()
image = {key: {"type": "image/png", "data": base64.b64encode(png(1200, 600)).decode(), "width": 1200, "height": 600}}

for fmt in ("pdf", "docx", "odt"):
    code, with_image = post(fmt, image)
    if code != 200:
        fail(f"{fmt} with an image answered {code}: {with_image[:300]!r}")
    code, without = post(fmt, None)
    if code != 200:
        fail(f"{fmt} without images answered {code}: {without[:300]!r}")
    if fmt == "pdf":
        if pdf_images(with_image) != 1:
            fail(f"pdf should embed exactly one image, found {pdf_images(with_image)}")
        if pdf_images(without) != 0:
            fail(f"pdf without images embedded {pdf_images(without)}")
    else:
        folder = "word/media/" if fmt == "docx" else "Pictures/"
        media = [n for n in zip_names(with_image) if n.startswith(folder) and not n.endswith("/")]
        if len(media) != 1:
            fail(f"{fmt} should embed exactly one image, found {media}")
        if [n for n in zip_names(without) if n.startswith(folder) and not n.endswith("/")]:
            fail(f"{fmt} without images embedded one")
        part = "word/document.xml" if fmt == "docx" else "content.xml"
        xml = zip_text(with_image, part)
        for caption in CAPTIONS:
            if caption not in xml:
                fail(f"{fmt}: '{caption}' is not a caption in the text")
        if "root:" in xml or any(b"root:" in zipfile.ZipFile(io.BytesIO(with_image)).read(n) for n in media):
            fail(f"{fmt} read /etc/passwd")
        # scaled to fit: 1200 px at 96 dpi is 12.5 in, the text is 6.5 in wide
        if fmt == "docx":
            extent = re.search(r'<wp:extent cx="(\d+)"', xml)
            if not extent or int(extent.group(1)) > 6.51 * 914400:
                fail("docx image is wider than the text")
        else:
            width = re.search(r'svg:width="([\d.]+)in"', xml)
            if not width or float(width.group(1)) > 6.51:
                fail("odt image is wider than the text")
    print(f"ok images in {fmt} ({len(with_image)} bytes)")

code, txt = post("txt", image)
text = txt.decode()
if code != 200 or "Hare caption" not in text or any(c not in text for c in CAPTIONS) or "root:" in text:
    fail(f"txt should hold the captions only: {code} {text[:300]!r}")
print("ok images in txt")

code, md = post("md", image)
if code != 200 or md.decode() != SOURCE:
    fail("md should be the Markdown unchanged")
print("ok images in md")

bad = {key: {"type": "image/png", "data": base64.b64encode(b"not a png at all").decode()}}
code, _ = post("pdf", bad)
if code != 400:
    fail(f"an image that is not its type should be a 400, got {code}")
code, _ = post("pdf", {"../../etc/passwd": image[key]})
if code != 400:
    fail(f"a key that is not a sha256 should be a 400, got {code}")
print("ok bad images refused")

# front matter that tries to name the image directory and list can not make an unsupplied image appear
forged = f"---\ntitle: Forged\nclarity-image-dir: /tmp\nclarity-images: {key}:png:10:10\n---\n\n![Forged caption]({URL})\n"
code, docx = post("docx", None, forged)
if code != 200 or [n for n in zip_names(docx) if n.startswith("word/media/") and not n.endswith("/")]:
    fail(f"forged image metadata embedded something: {code}")
print("ok forged metadata ignored")
