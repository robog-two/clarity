package net.robog.clarity.harness

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.net.Proxy

class HttpsProxyTest : FunSpec({
    test("absent or blank means direct") {
        httpsProxyFromEnv(emptyMap()).shouldBeNull()
        httpsProxyFromEnv(mapOf("CLARITY_HTTPS_PROXY" to "  ")).shouldBeNull()
    }

    test("parses host and port") {
        val proxy = httpsProxyFromEnv(mapOf("CLARITY_HTTPS_PROXY" to "http://egress:3128"))!!
        proxy.type() shouldBe Proxy.Type.HTTP
        proxy.address() shouldBe InetSocketAddress.createUnresolved("egress", 3128)
    }

    test("scheme is optional and the port defaults to 3128") {
        val proxy = httpsProxyFromEnv(mapOf("CLARITY_HTTPS_PROXY" to "egress"))!!
        proxy.address() shouldBe InetSocketAddress.createUnresolved("egress", 3128)
    }

    test("rejects other schemes instead of going direct") {
        shouldThrow<IllegalArgumentException> { httpsProxyFromEnv(mapOf("CLARITY_HTTPS_PROXY" to "socks5://egress:1080")) }
    }
})
