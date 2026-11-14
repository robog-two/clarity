package net.robog.clarity.app.data

import java.net.URLDecoder
import net.robog.clarity.app.net.SourceFile
import net.robog.clarity.app.net.SourcesApi
import net.robog.clarity.shared.source.SourceView

/** The records the agent cites, read-only: one as stored, and a message's attachment as bytes. */
interface SourcesRepository {
    suspend fun view(id: String): ApiResult<SourceView>
    suspend fun attachment(entityId: String, part: String): ApiResult<SourceFile>
}

class DefaultSourcesRepository(private val api: SourcesApi) : SourcesRepository {
    override suspend fun view(id: String): ApiResult<SourceView> = api.source(id)
    override suspend fun attachment(entityId: String, part: String): ApiResult<SourceFile> = api.attachment(entityId, part)
}

private val extended = Regex("""filename\*\s*=\s*([^';]*)'[^';]*'([^;]+)""", RegexOption.IGNORE_CASE)
private val quoted = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
private val bare = Regex("""filename\s*=\s*([^;"]+)""", RegexOption.IGNORE_CASE)

/** Pure: the file name in a `Content-Disposition` header (`filename*` wins over `filename`); null when there is none. */
fun dispositionFilename(header: String): String? {
    val name = extended.find(header)?.let { m ->
        runCatching { URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), m.groupValues[1].ifBlank { "UTF-8" }) }.getOrNull()
    } ?: quoted.find(header)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
        ?: bare.find(header)?.groupValues?.get(1)?.trim()
    return name?.takeIf(String::isNotBlank)
}
