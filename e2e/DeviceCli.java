// A stand-in for the Android app's networking, for the end-to-end stack test (e2e/run.sh).
// Run with a JDK (17+), no build step:  java e2e/DeviceCli.java <command> ...
//
//   enroll  <state-dir> <base-url> [enrollment-token]   generate an EC P-256 key (once), enroll, remember the device id
//   token   <state-dir>                                  print a fresh device JWT (ES256, 60 s), like the app does per request
//   request <state-dir> <METHOD> <url> [json-body]       signed request; body to stdout, exit 22 on a non-2xx status
//   sse     <state-dir> <url> <seconds>                  signed SSE stream; prints lines for <seconds>, exit 0 once an event arrived
//
// The state directory holds key.pk8 (private key), key.pub (SubjectPublicKeyInfo) and device-id.
// Claims match backend DeviceJwtVerification: aud=clarity, sub=kid=device id, iat/exp (<= 300 s), jti.
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

public class DeviceCli {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        Path state = Path.of(args[1]);
        switch (args[0]) {
            case "enroll" -> {
                if (args.length < 3) usage();
                System.out.println(enroll(state, args[2], args.length > 3 ? args[3] : null));
            }
            case "token" -> System.out.println(token(state));
            case "request" -> {
                if (args.length < 4) usage();
                System.exit(request(state, args[2], args[3], args.length > 4 ? args[4] : null));
            }
            case "sse" -> {
                if (args.length < 4) usage();
                System.exit(sse(state, args[2], Integer.parseInt(args[3])));
            }
            default -> usage();
        }
    }

    static void usage() {
        System.err.println("usage: java DeviceCli.java enroll|token|request|sse <state-dir> ... (see the header of this file)");
        System.exit(2);
    }

    // ---- keys and tokens -------------------------------------------------------------------

    static KeyPair loadOrCreateKey(Path state) throws Exception {
        Files.createDirectories(state);
        Path pk8 = state.resolve("key.pk8");
        Path pub = state.resolve("key.pub");
        KeyFactory ec = KeyFactory.getInstance("EC");
        if (Files.exists(pk8) && Files.exists(pub)) {
            PrivateKey priv = ec.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(pk8)));
            return new KeyPair(ec.generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(pub))), priv);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Files.write(pk8, pair.getPrivate().getEncoded());
        Files.write(pub, pair.getPublic().getEncoded());
        return pair;
    }

    static String sign(KeyPair pair, String deviceId, Instant now, long lifetimeSeconds) throws Exception {
        String header = "{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"" + deviceId + "\"}";
        String claims = "{\"sub\":\"" + deviceId + "\",\"aud\":\"clarity\",\"iat\":" + now.getEpochSecond()
            + ",\"exp\":" + (now.getEpochSecond() + lifetimeSeconds) + ",\"jti\":\"" + UUID.randomUUID() + "\"}";
        String signingInput = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        // JOSE wants the raw r||s form (RFC 7518 3.4), which the JCA can produce directly.
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(pair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64.encodeToString(signature.sign());
    }

    static String token(Path state) throws Exception {
        return sign(loadOrCreateKey(state), Files.readString(state.resolve("device-id")).trim(), Instant.now(), 60);
    }

    // ---- commands --------------------------------------------------------------------------

    static String enroll(Path state, String baseUrl, String enrollmentToken) throws Exception {
        KeyPair pair = loadOrCreateKey(state);
        String body = "{\"publicKey\":\"" + B64.encodeToString(pair.getPublic().getEncoded()) + "\",\"deviceName\":\"e2e\""
            + (enrollmentToken == null || enrollmentToken.isBlank() ? "" : ",\"enrollmentToken\":\"" + enrollmentToken + "\"") + "}";
        HttpResponse<String> response = HTTP.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/enroll")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            System.err.println("enroll failed: " + response.statusCode() + " " + response.body());
            System.exit(22);
        }
        var matcher = Pattern.compile("\"deviceId\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        if (!matcher.find()) throw new IllegalStateException("no deviceId in " + response.body());
        Files.writeString(state.resolve("device-id"), matcher.group(1));
        return response.body();
    }

    static int request(Path state, String method, String url, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token(state));
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method.toUpperCase(), body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            System.err.println(response.statusCode() + " " + response.body());
            return 22;
        }
        System.out.println(response.body());
        return 0;
    }

    static int sse(Path state, String url, int seconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token(state))
            .header("Accept", "text/event-stream").build();
        HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            System.err.println("sse: status " + response.statusCode());
            return 22;
        }
        boolean[] sawEvent = {false};
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    System.out.println(line);
                    if (line.startsWith("data:")) sawEvent[0] = true;
                }
            } catch (Exception ignored) {
                // closed by the deadline
            }
        });
        reader.setDaemon(true);
        reader.start();
        reader.join(seconds * 1000L);
        System.out.flush();
        return sawEvent[0] ? 0 : 1;
    }
}
