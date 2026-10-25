package net.robog.clarity.backend.crypto

import java.security.MessageDigest

/** SHA-256 digest of [bytes]. A fresh [MessageDigest] per call keeps this trivially thread-safe. */
fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** SHA-256 digest of the UTF-8 encoding of [text]. */
fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))
