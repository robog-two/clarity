-- What a report must never carry into a rendered file: raw output-format code (Word XML, ODF, Typst,
-- HTML, TeX), images (pandoc would read the named file or URL and embed it), and metadata beyond the
-- title block (a `template`, `bibliography` or `header-includes` could reach files or inject code).

local KEEP_META = { title = true, subtitle = true, author = true, date = true, abstract = true, lang = true, keywords = true }

function Meta(meta)
  for key, _ in pairs(meta) do
    if not KEEP_META[key] then
      meta[key] = nil
    end
  end
  return meta
end

function RawBlock(_)
  return {}
end

function RawInline(_)
  return {}
end

-- An image becomes its description.
function Image(image)
  return image.caption
end
