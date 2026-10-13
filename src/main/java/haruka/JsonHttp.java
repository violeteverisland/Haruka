package haruka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** 两个 provider 共用的 JSON 请求小事。 */
final class JsonHttp {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private JsonHttp() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    static JsonNode postJson(String url, Map<String, String> headers, JsonNode body,
                             int timeoutSeconds, String provider) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(request, provider);
    }

    static JsonNode getJson(String url, Map<String, String> headers,
                            int timeoutSeconds, String provider) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json");
        headers.forEach(builder::header);
        return send(builder.GET().build(), provider);
    }

    private static JsonNode send(HttpRequest request, String provider) throws Exception {
        HttpResponse<String> resp = CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException(provider + " 返回 HTTP " + resp.statusCode() + ": "
                    + abbreviate(resp.body()));
        }
        return MAPPER.readTree(resp.body());
    }

    /** 取二进制内容（头像、图片）。失败抛异常，由调用方决定这事要不要紧。 */
    static byte[] getBytes(String url, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        HttpResponse<byte[]> resp = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 按扩展名猜 MIME。给模型传图时必须给对。 */
    static String guessMime(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    static String joinText(JsonNode parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null && parts.isArray()) {
            for (JsonNode part : parts) {
                String t = part.path("text").asText("");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
        }
        return sb.toString().trim();
    }
}
