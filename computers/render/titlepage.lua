-- Puts the title block on a page of its own: a page break before the body, for Word and Typst (PDF).
-- Runs after sanitize.lua, so these are the only raw blocks in the document.

function Pandoc(doc)
  if doc.meta.title == nil then
    return doc
  end
  local brk
  if FORMAT:match("docx") then
    brk = pandoc.RawBlock("openxml", '<w:p><w:r><w:br w:type="page"/></w:r></w:p>')
  elseif FORMAT:match("typst") then
    brk = pandoc.RawBlock("typst", "#pagebreak()")
  else
    return doc
  end
  table.insert(doc.blocks, 1, brk)
  return doc
end
