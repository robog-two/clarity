package net.robog.clarity.backend.evals

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import net.robog.clarity.backend.auth.DeviceJwtVerification

/**
 * The end-to-end test's device CLI (`e2e/DeviceCli.java`) signs the same JWTs as the app: its
 * tokens must pass the backend's own verification. Skipped where the source file is not reachable.
 */
class DeviceCliTest : FunSpec({
    val source = listOf("../e2e/DeviceCli.java", "e2e/DeviceCli.java").map(::File).firstOrNull { it.exists() }

    test("a token from the e2e device CLI passes the backend's device JWT verification").config(enabled = source != null) {
        val state = Files.createTempDirectory("e2e-device").toFile()
        state.resolve("device-id").writeText("dev-123")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, source!!.path, "token", state.path).redirectErrorStream(false).start()
        val token = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(60, TimeUnit.SECONDS) shouldBe true
        process.exitValue() shouldBe 0

        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(state.resolve("key.pub").readBytes())) as ECPublicKey
        val verified = DeviceJwtVerification.verify(token, key, Clock.System.now())
        (verified as Either.Right).value.deviceId shouldBe "dev-123"
        DeviceJwtVerification.unverifiedKeyId(token) shouldBe "dev-123"
        state.deleteRecursively()
    }
})
