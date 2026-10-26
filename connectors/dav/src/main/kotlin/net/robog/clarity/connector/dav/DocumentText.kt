package net.robog.clarity.connector.dav

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Files larger than this are indexed by name only. */
const val MAX_EXTRACT_BYTES = 20L * 1024 * 1024

private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "tsv", "json", "yaml", "yml", "xml", "log", "ini", "toml", "org", "rst", "tex", "ics", "vcf")

/** MIME type for a file name when the server does not tell. */
fun mimeFor(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> "text/markdown"
    "txt", "log", "org", "rst", "tex" -> "text/plain"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    "pdf" -> "application/pdf"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "json" -> "application/json"
    "xml" -> "application/xml"
    else -> null
}

/**
 * Plain text of a file, or null when the format is not one we can read (images, archives...).
 * Never throws for malformed input: a damaged PDF simply has no text.
 */
fun extractText(path: String, mimeType: String?, bytes: ByteArray): String? {
    val ext = path.substringAfterLast('.', "").lowercase()
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    return try {
        when {
            ext == "pdf" || mime == "application/pdf" -> pdfText(bytes)
            ext == "odt" || mime == "application/vnd.oasis.opendocument.text" -> odtText(bytes)
            ext == "html" || ext == "htm" || mime == "text/html" -> Jsoup.parse(bytes.toString(Charsets.UTF_8)).apply { select("script,style").remove() }.text()
            ext in TEXT_EXTENSIONS || mime?.startsWith("text/") == true || mime == "application/json" || mime == "application/xml" ->
                bytes.toString(Charsets.UTF_8)
            else -> null
        }?.replace("\u0000", "")?.trim()
    } catch (_: Exception) {
        null
    }
}

private fun pdfText(bytes: ByteArray): String =
    Loader.loadPDF(bytes).use { doc -> if (doc.isEncrypted) "" else PDFTextStripper().getText(doc) }

private fun odtText(bytes: ByteArray): String? {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            if (entry.name == "content.xml") {
                val xml = Jsoup.parse(zip.readBytes().toString(Charsets.UTF_8), "", Parser.xmlParser())
                xml.select("text|p, text|h").prepend("\\n")
                return xml.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n")
            }
        }
    }
    return null
}
