-- What a report must never carry into a rendered file: raw output-format code (Word XML, ODF, Typst,
-- HTML, TeX), images (pandoc would read the named file or URL and embed it), and metadata beyond the
-- title block (a `template`, `bibliography` or `header-includes` could reach files or inject code).
--
-- An image is kept only when the backend fetched it: renderd puts those files in a fresh directory as
-- <sha256 of the URL as written>.<ext> and names them on the command line (-M, which wins over any
-- metadata the document itself declares). Every other image, whatever its address (a local path,
-- file:, a data URI, a URL nobody fetched), becomes its caption.

local KEEP_META = { title = true, subtitle = true, author = true, date = true, abstract = true, lang = true, keywords = true }

-- SHA-256 in plain Lua (pandoc has only sha1), to match the backend's key for a URL.
local K = {
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
}

local function rotr(x, n)
  return ((x >> n) | (x << (32 - n))) & 0xffffffff
end

local function sha256(msg)
  local h0, h1, h2, h3, h4, h5, h6, h7 =
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  local length = #msg
  msg = msg .. "\128" .. string.rep("\0", (55 - length) % 64) .. string.pack(">I8", length * 8)
  local w = {}
  for chunk = 1, #msg, 64 do
    for i = 0, 15 do
      w[i] = string.unpack(">I4", msg, chunk + i * 4)
    end
    for i = 16, 63 do
      local s0 = rotr(w[i - 15], 7) ~ rotr(w[i - 15], 18) ~ (w[i - 15] >> 3)
      local s1 = rotr(w[i - 2], 17) ~ rotr(w[i - 2], 19) ~ (w[i - 2] >> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) & 0xffffffff
    end
    local a, b, c, d, e, f, g, h = h0, h1, h2, h3, h4, h5, h6, h7
    for i = 0, 63 do
      local S1 = rotr(e, 6) ~ rotr(e, 11) ~ rotr(e, 25)
      local ch = (e & f) ~ ((~e & 0xffffffff) & g)
      local t1 = (h + S1 + ch + K[i + 1] + w[i]) & 0xffffffff
      local S0 = rotr(a, 2) ~ rotr(a, 13) ~ rotr(a, 22)
      local maj = (a & b) ~ (a & c) ~ (b & c)
      local t2 = (S0 + maj) & 0xffffffff
      h, g, f, e, d, c, b, a = g, f, e, (d + t1) & 0xffffffff, c, b, a, (t1 + t2) & 0xffffffff
    end
    h0, h1, h2, h3 = (h0 + a) & 0xffffffff, (h1 + b) & 0xffffffff, (h2 + c) & 0xffffffff, (h3 + d) & 0xffffffff
    h4, h5, h6, h7 = (h4 + e) & 0xffffffff, (h5 + f) & 0xffffffff, (h6 + g) & 0xffffffff, (h7 + h) & 0xffffffff
  end
  return string.format("%08x%08x%08x%08x%08x%08x%08x%08x", h0, h1, h2, h3, h4, h5, h6, h7)
end

local MIME = { png = "image/png", jpg = "image/jpeg", gif = "image/gif", webp = "image/webp" }

local images = {}      -- sha256 of the URL -> { name, widthIn }
local loaded = false

local function text(value)
  return value and pandoc.utils.stringify(value) or ""
end

-- Reads what the command line gave: clarity-image-dir, clarity-text-width-in, and clarity-images as
-- "<key>:<ext>:<width px>:<height px>" entries separated by spaces. The files go into the media bag
-- (pandoc's --sandbox lets its writers read nothing else).
local function load_images(meta)
  if loaded then return end
  loaded = true
  local dir = text(meta["clarity-image-dir"])
  local limit = tonumber(text(meta["clarity-text-width-in"])) or 6.5
  if dir == "" then return end
  for entry in text(meta["clarity-images"]):gmatch("%S+") do
    local key, ext, w = entry:match("^(%x+):(%a+):(%d*):%d*$")
    if key and #key == 64 and key == key:lower() and MIME[ext] then
      local file = io.open(dir .. "/" .. key .. "." .. ext, "rb")
      if file then
        local contents = file:read("a")
        file:close()
        local name = key .. "." .. ext
        pandoc.mediabag.insert(name, MIME[ext], contents)
        -- natural size at 96 dpi, never wider than the text
        local width = tonumber(w)
        local inches = limit
        if width and width > 0 then inches = math.min(width / 96, limit) end
        images[key] = { name = name, widthIn = inches }
      end
    end
  end
end

-- Pass 1 reads the command-line metadata; pass 2 sanitizes.
local reader = {
  Pandoc = function(doc)
    load_images(doc.meta)
    return nil
  end,
}

local cleaner = {
  Meta = function(meta)
    for key, _ in pairs(meta) do
      if not KEEP_META[key] then
        meta[key] = nil
      end
    end
    return meta
  end,

  RawBlock = function(_)
    return {}
  end,

  RawInline = function(_)
    return {}
  end,

  -- An image the backend fetched stays; every other one becomes its description. Plain text always
  -- gets the description.
  Image = function(image)
    local kept = FORMAT ~= "plain" and images[sha256(image.src)]
    if not kept then
      return image.caption
    end
    image.src = kept.name
    image.attributes = { width = string.format("%.2fin", kept.widthIn) }
    return image
  end,
}

return { reader, cleaner }
