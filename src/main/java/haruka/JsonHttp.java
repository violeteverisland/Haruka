package haruka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** 两个 provider 共用的 JSON 请求小事。 */
final class JsonHttp {

    private static final Log log = Log.get(JsonHttp.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 连接层失败时重发几次。
     *
     * <p>起因是一次真实故障：中转站接下了请求、去够它的上游、没够到就直接把连接掐了，
     * Java 报 {@code HTTP/1.1 header parser received no bytes}。这种抖动重发一次就好了，
     * 而当时的处理是只写一行日志、群里什么都不回 —— 看起来就是"她突然不说话了"。
     *
     * <p>只重试**连接层**的失败。HTTP 4xx/5xx 是对方给出的明确答复，重发没有意义
     * （429 该退避而不是立刻重发）；超时也不重试 —— 那说明对方在认真处理，
     * 重发可能白花一次 token。
     */
    private static final int RETRY = 1;
    private static final long RETRY_DELAY_MS = 300;

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
        IOException last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try {
                HttpResponse<String> resp = CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException(provider + " 返回 HTTP " + resp.statusCode() + ": "
                            + abbreviate(resp.body()));
                }
                return MAPPER.readTree(resp.body());
            } catch (IOException e) {
                last = e;
                if (e instanceof HttpTimeoutException || attempt == RETRY) {
                    break;
                }
                log.warn("{} 请求失败（{}），重发一次", provider, describe(e));
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(provider + " 请求被中断", ie);
                }
            }
        }
        throw new IOException(provider + " 请求失败：" + describe(last), last);
    }

    /** 把连接层异常翻译成人能看懂的一句话。原始信息对使用者毫无意义。 */
    private static String describe(IOException e) {
        if (e == null) {
            return "未知错误";
        }
        if (e instanceof HttpTimeoutException) {
            return "等待响应超时（对方一直没给完整响应）";
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("header parser received no bytes")) {
            return "对方连接后没有返回任何内容就断开了（不是超时，也不是 HTTP 错误码）"
                    + " —— 通常是中转/反代那边的瞬时故障";
        }
        if (m.contains("Connection reset")) {
            return "连接被对方重置";
        }
        if (m.contains("Connection refused")) {
            return "对方拒绝连接（地址或端口不对？服务没起？）";
        }
        return Log.rootMessage(e);
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

    /**
     * 把回复的 parts 拼成文本。
     *
     * <p><b>必须跳过 {@code thought:true} 的片段</b> —— 那是模型的思考草稿，不是回复内容。
     * 漏掉这一步的症状很显眼：模型把内部推理直接吐给用户，实测见过英文的
     * "Initial assessment: ..." 顶在正常中文回复前面。
     *
     * <p>画图那边早就做了同样的过滤（见 {@link ImageClient}），聊天这边一直漏着 ——
     * 所以这里特意把方法名写成 joinReplyText，而不是含糊的 joinText：
     * 方法名一旦看不出"这是回复正文"，下一个人就会顺手把思考片段也拼进来。
     */
    static String joinReplyText(JsonNode parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null && parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.path("thought").asBoolean(false)) {
                    continue;
                }
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
