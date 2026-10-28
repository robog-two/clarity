package net.robog.clarity.app.data

import net.robog.clarity.app.net.ReportsApi
import net.robog.clarity.shared.report.ReportDetail
import net.robog.clarity.shared.report.ReportSummary

/** A Good's download page: its full URL and how many files were downloaded through it. */
data class GoodsLink(val url: String, val downloads: Long)

/**
 * The person's Goods (reports): the list, one with its source, and its secret download page. The
 * app never downloads the files itself: Download opens the page in the browser and Share sends its
 * link, and the page has every format.
 */
interface GoodsRepository {
    suspend fun list(): ApiResult<List<ReportSummary>>
    suspend fun detail(id: String): ApiResult<ReportDetail>
    suspend fun source(id: String): ApiResult<String>

    /** The download page's full URL (the same one until it is turned off). */
    suspend fun link(id: String): ApiResult<GoodsLink>
    suspend fun turnOffLink(id: String): ApiResult<Unit>
}

class DefaultGoodsRepository(private val api: ReportsApi) : GoodsRepository {
    override suspend fun list(): ApiResult<List<ReportSummary>> = when (val r = api.reports()) {
        is ApiResult.Success -> ApiResult.Success(r.value.reports)
        is ApiResult.Failure -> r
    }

    override suspend fun detail(id: String): ApiResult<ReportDetail> = api.report(id)
    override suspend fun source(id: String): ApiResult<String> = api.reportSource(id)
    override suspend fun link(id: String): ApiResult<GoodsLink> = api.reportLink(id)
    override suspend fun turnOffLink(id: String): ApiResult<Unit> = api.revokeReportLink(id)
}
