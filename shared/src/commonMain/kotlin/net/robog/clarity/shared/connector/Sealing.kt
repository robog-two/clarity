package net.robog.clarity.shared.connector

/**
 * How secret connector config values (passwords) are sealed so that only the connector can read
 * them. The app encrypts; the backend stores [SealedValue]s it cannot open; the connector decrypts.
 *
 * Scheme: HPKE (RFC 9180) base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM.
 * Both sides use Google Tink's HPKE (pure Java, works on the JVM and Android).
 * - [ConnectorManifest.sealingKey]: the connector's raw 32-byte X25519 public key, base64url, no padding.
 *   The connector generates the key pair once and keeps the private key in its `/data` volume.
 * - info: `"$INFO_PREFIX<connectorId>"` (UTF-8), so a value sealed for one connector is useless to another.
 * - aad: the config field name (UTF-8), so values can't be swapped between fields.
 * - [SealedValue.ciphertext]: base64url (no padding) of `enc || ciphertext` (enc = 32 bytes).
 */
object Sealing {
    const val ALGORITHM = "HPKE-Base-X25519-HKDF-SHA256-AES-256-GCM"
    const val INFO_PREFIX = "clarity-connector-config-v1:"
    const val ENC_LENGTH = 32
}
