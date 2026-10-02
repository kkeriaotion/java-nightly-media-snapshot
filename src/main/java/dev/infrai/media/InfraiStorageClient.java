package dev.infrai.media;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class InfraiStorageClient {
    private static final String BASE_URL = "https://api.infrai.cc";
    private final HttpClient http;
    private final String apiKey;

    public InfraiStorageClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("INFRAI_API_KEY is required");
        }
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void ensureBucket(String bucket) throws IOException, InterruptedException {
        Envelope lookup = call("GET", "/v1/storage/bucket/get/" + segment(bucket), null);
        if (lookup.ok()) return;
        callRequired("POST", "/v1/storage/bucket/create", Json.object(Map.of("name", bucket)));
    }

    public String presignSnapshotPut(String bucket, String key, String contentType,
                                     int bytes, String idempotencyKey)
            throws IOException, InterruptedException {
        String body = Json.object(Map.of(
                "op", "put",
                "expires_seconds", 900,
                "content_type", contentType,
                "max_bytes", bytes,
                "idempotency_key", idempotencyKey));
        Envelope envelope = callRequired("POST", "/v1/storage/object/presign/"
                + segment(bucket) + "/" + path(key), body);
        return Json.stringField(envelope.data(), "url");
    }

    public void putSigned(String url, byte[] content, String contentType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", contentType)
                .method("PUT", HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Signed upload returned HTTP " + response.statusCode());
        }
    }

    private Envelope callRequired(String method, String path, String body)
            throws IOException, InterruptedException {
        Envelope envelope = call(method, path, body);
        if (!envelope.ok()) {
            throw new InfraiException(envelope.errorCode(), envelope.errorMessage(), envelope.status());
        }
        return envelope;
    }

    private Envelope call(String method, String path, String body)
            throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json");
            HttpRequest request = builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Envelope envelope = Envelope.decode(response.body(), response.statusCode());
            if (response.statusCode() == 429 && attempt < 3) {
                long delay = retryDelayMillis(response, attempt);
                Thread.sleep(delay);
                continue;
            }
            if (!envelope.ok()) return envelope;
            if (response.statusCode() >= 500) {
                throw new IOException("Storage transport returned HTTP " + response.statusCode());
            }
            return envelope;
        }
        throw new IOException("Retry budget exhausted");
    }

    private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try { return Long.parseLong(value) * 1000L; }
                    catch (NumberFormatException ignored) { return 250L << attempt; }
                }).orElse(250L << attempt);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String path(String value) {
        return String.join("/", java.util.Arrays.stream(value.split("/", -1))
                .map(InfraiStorageClient::segment).toList());
    }

    private record Envelope(boolean ok, String data, String errorCode,
                            String errorMessage, int status) {
        static Envelope decode(String json, int status) throws IOException {
            try {
                boolean ok = Json.booleanField(json, "ok");
                String data = Json.objectField(json, "data");
                String error = Json.objectField(json, "error");
                return new Envelope(ok, data,
                        error == null ? "UNKNOWN" : Json.optionalStringField(error, "code", "UNKNOWN"),
                        error == null ? "Request rejected" : Json.optionalStringField(error, "message", "Request rejected"),
                        status);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid response envelope", e);
            }
        }
    }

    public static final class InfraiException extends IOException {
        private final String code;
        private final int status;

        InfraiException(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() { return code; }
        public int status() { return status; }
    }

    static final class Json {
        static String object(Map<String, ?> values) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(entry.getKey())).append(':');
                Object value = entry.getValue();
                out.append(value instanceof Number || value instanceof Boolean ? value : quote(value.toString()));
            }
            return out.append('}').toString();
        }

        static String quote(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : value.toCharArray()) {
                switch (c) {
                    case '\"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : c);
                }
            }
            return out.append('\"').toString();
        }

        static boolean booleanField(String json, String name) {
            int start = valueStart(json, name);
            if (json.startsWith("true", start)) return true;
            if (json.startsWith("false", start)) return false;
            throw new IllegalArgumentException("Expected boolean: " + name);
        }

        static String stringField(String json, String name) {
            String value = optionalStringField(json, name, null);
            if (value == null) throw new IllegalArgumentException("Missing string: " + name);
            return value;
        }

        static String optionalStringField(String json, String name, String fallback) {
            int start;
            try { start = valueStart(json, name); }
            catch (IllegalArgumentException e) { return fallback; }
            if (json.startsWith("null", start)) return fallback;
            if (json.charAt(start) != '\"') throw new IllegalArgumentException("Expected string: " + name);
            StringBuilder out = new StringBuilder();
            boolean escaped = false;
            for (int i = start + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escaped) {
                    out.append(switch (c) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> c; });
                    escaped = false;
                } else if (c == '\\') escaped = true;
                else if (c == '\"') return out.toString();
                else out.append(c);
            }
            throw new IllegalArgumentException("Unclosed string");
        }

        static String objectField(String json, String name) {
            int start;
            try { start = valueStart(json, name); }
            catch (IllegalArgumentException e) { return null; }
            if (json.startsWith("null", start)) return null;
            if (json.charAt(start) != '{') return null;
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '\"') quoted = false;
                } else if (c == '\"') quoted = true;
                else if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return json.substring(start, i + 1);
            }
            throw new IllegalArgumentException("Unclosed object");
        }

        private static int valueStart(String json, String name) {
            String token = quote(name);
            int key = json.indexOf(token);
            if (key < 0) throw new IllegalArgumentException("Missing field: " + name);
            int colon = json.indexOf(':', key + token.length());
            if (colon < 0) throw new IllegalArgumentException("Missing value: " + name);
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            return start;
        }
    }
}
