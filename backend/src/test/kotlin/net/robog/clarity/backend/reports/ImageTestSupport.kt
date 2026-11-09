package net.robog.clarity.backend.reports

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.exposed.v1.jdbc.Database

/** A fetcher with no network: every image fails. */
object NoImageFetcher : ImageFetcher {
    override suspend fun fetch(url: String): ImageOutcome = ImageOutcome.Failed("no network in tests")
}

/** A fetcher that answers from [images] by URL (a miss fails) and records what it was asked. */
class MapImageFetcher(private val images: Map<String, FetchedImage>) : ImageFetcher {
    val asked = java.util.concurrent.CopyOnWriteArrayList<String>()

    override suspend fun fetch(url: String): ImageOutcome {
        asked += url
        return images[url]?.let { ImageOutcome.Fetched(it) } ?: ImageOutcome.Failed("not found")
    }
}

fun testImages(database: Database, fetcher: ImageFetcher = NoImageFetcher): ReportImages =
    ReportImages(ExposedReportImageStore(database), fetcher, CoroutineScope(SupervisorJob()), Clock.System)

/** A real image of [width] x [height] in [format] (png, jpg or gif), made by the JDK. */
fun testImage(format: String = "png", width: Int = 3, height: Int = 2): ByteArray {
    val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val out = java.io.ByteArrayOutputStream()
    check(javax.imageio.ImageIO.write(image, format, out))
    return out.toByteArray()
}
