package net.robog.clarity.harness

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * The egress proxy for the model API, from `CLARITY_HTTPS_PROXY` (e.g. `http://egress:3128`).
 * Blank or absent means a direct connection (local development); an unparsable value is an error
 * rather than silently going direct.
 */
fun httpsProxyFromEnv(env: Map<String, String>): Proxy? {
    val raw = env["CLARITY_HTTPS_PROXY"]?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val uri = URI(if ("://" in raw) raw else "http://$raw")
    require(uri.scheme == "http" && uri.host != null) { "CLARITY_HTTPS_PROXY must be http://host[:port], got '$raw'" }
    return Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.host, if (uri.port > 0) uri.port else 3128))
}
