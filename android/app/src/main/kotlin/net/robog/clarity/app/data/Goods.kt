package net.robog.clarity.app.data

import java.io.File
import net.robog.clarity.app.net.ReportsApi
import net.robog.clarity.shared.report.ReportDetail
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportSummary

/** A downloaded file of a Good, in the app's cache, and the type to open it as. */
data class GoodsFile(val file: File, val mimeType: String)

/**
 * The person's Goods (reports): the list, one with its source, and downloads. Downloads go through
 * the authenticated client into the app's cache ([cacheDir]`/goods`, shared with other apps only
 * through the FileProvider when the person opens or shares one); the system may clear them any time.
 */
interface GoodsRepository {
    suspend fun list(): ApiResult<List<ReportSummary>>
    suspend fun detail(id: String): ApiResult<ReportDetail>
    suspend fun source(id: String): ApiResult<String>
    suspend fun download(id: String, format: String, title: String): ApiResult<GoodsFile>
}

class DefaultGoodsRepository(private val api: ReportsApi, private val cacheDir: () -> File) : GoodsRepository {
    override suspend fun list(): ApiResult<List<ReportSummary>> = when (val r = api.reports()) {
        is ApiResult.Success -> ApiResult.Success(r.value.reports)
        is ApiResult.Failure -> r
    }

    override suspend fun detail(id: String): ApiResult<ReportDetail> = api.report(id)

    override suspend fun source(id: String): ApiResult<String> = api.reportSource(id)

    override suspend fun download(id: String, format: String, title: String): ApiResult<GoodsFile> {
        val target = File(cacheDir(), goodsFilePath(id, title, format))
        return when (val r = api.downloadReport(id, format, target)) {
            is ApiResult.Success -> ApiResult.Success(GoodsFile(target, goodsMimeType(format)))
            is ApiResult.Failure -> r
        }
    }
}

/** The cache folder downloads go in (also named in res/xml/file_paths.xml). */
const val GOODS_CACHE_DIR = "goods"

/**
 * Pure: where a download is kept under the cache: `goods/<id>/<title>.<ext>`. The id and title are
 * made safe for a file name, so nothing from the server can name a path outside the folder.
 */
fun goodsFilePath(id: String, title: String, format: String): String {
    val folder = id.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "report" }
    val extension = (ReportFormat.fromWire(format)?.extension ?: format.filter { it.isLetterOrDigit() }.take(8)).ifEmpty { "bin" }
    return "$GOODS_CACHE_DIR/$folder/${goodsFileName(title)}.$extension"
}

/** Pure: a title as a file name (letters, digits, spaces, `-` and `_`; at most 80 characters). */
fun goodsFileName(title: String): String =
    title.map { c -> if (c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_') c else ' ' }
        .joinToString("").trim().replace(Regex("\\s+"), " ").take(80).trim().ifEmpty { "Document" }

/** Pure: the media type to open a [format] (wire name) download as. */
fun goodsMimeType(format: String): String =
    ReportFormat.fromWire(format)?.mimeType?.substringBefore(';')?.trim() ?: "application/octet-stream"
