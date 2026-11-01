// Seals a connector config secret the way the app does (shared/.../connector/Sealing.kt), with the JDK
// only: HPKE (RFC 9180) base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM, info =
// "clarity-connector-config-v1:<connectorId>:<field>", output base64url(enc || ciphertext).
//
//   java e2e/mcp/Seal.java <sealing-key-base64url> <connector-id> <field>   (plaintext on stdin)
//
// Prints the stored form: {"type":"sealed","algorithm":"...","ciphertext":"..."}.
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Seal {
    static final byte[] KEM_SUITE = cat(ascii("KEM"), new byte[] {0x00, 0x20});
    static final byte[] HPKE_SUITE = cat(ascii("HPKE"), new byte[] {0x00, 0x20, 0x00, 0x01, 0x00, 0x02});

    public static void main(String[] args) throws Exception {
        byte[] pkR = Base64.getUrlDecoder().decode(args[0]);
        byte[] info = ascii("clarity-connector-config-v1:" + args[1] + ":" + args[2]);
        byte[] plaintext = System.in.readAllBytes();

        KeyPair eph = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] enc = raw((XECPublicKey) eph.getPublic());
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(eph.getPrivate());
        ka.doPhase(KeyFactory.getInstance("X25519").generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, littleEndian(pkR))), true);
        byte[] dh = ka.generateSecret();

        byte[] eaePrk = labeledExtract(KEM_SUITE, new byte[0], "eae_prk", dh);
        byte[] shared = labeledExpand(KEM_SUITE, eaePrk, "shared_secret", cat(enc, pkR), 32);

        byte[] pskIdHash = labeledExtract(HPKE_SUITE, new byte[0], "psk_id_hash", new byte[0]);
        byte[] infoHash = labeledExtract(HPKE_SUITE, new byte[0], "info_hash", info);
        byte[] context = cat(new byte[] {0x00}, pskIdHash, infoHash);
        byte[] secret = labeledExtract(HPKE_SUITE, shared, "secret", new byte[0]);
        byte[] key = labeledExpand(HPKE_SUITE, secret, "key", context, 32);
        byte[] nonce = labeledExpand(HPKE_SUITE, secret, "base_nonce", context, 12);

        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ct = gcm.doFinal(plaintext);
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(cat(enc, ct));
        System.out.println("{\"type\":\"sealed\",\"algorithm\":\"HPKE-Base-X25519-HKDF-SHA256-AES-256-GCM\",\"ciphertext\":\"" + b64 + "\"}");
    }

    static byte[] labeledExtract(byte[] suite, byte[] salt, String label, byte[] ikm) throws Exception {
        return hmac(salt.length == 0 ? new byte[32] : salt, cat(ascii("HPKE-v1"), suite, ascii(label), ikm));
    }

    static byte[] labeledExpand(byte[] suite, byte[] prk, String label, byte[] info, int length) throws Exception {
        byte[] labeled = cat(new byte[] {(byte) (length >> 8), (byte) length}, ascii("HPKE-v1"), suite, ascii(label), info);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        for (int i = 1; out.size() < length; i++) {
            t = hmac(prk, cat(t, labeled, new byte[] {(byte) i}));
            out.write(t);
        }
        byte[] okm = new byte[length];
        System.arraycopy(out.toByteArray(), 0, okm, 0, length);
        return okm;
    }

    static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** The raw 32-byte little-endian u-coordinate of an X25519 public key. */
    static byte[] raw(XECPublicKey key) {
        byte[] be = key.getU().toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static BigInteger littleEndian(byte[] raw) {
        byte[] be = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) be[i] = raw[raw.length - 1 - i];
        be[0] &= 0x7f; // RFC 7748: the top bit is ignored
        return new BigInteger(1, be);
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
