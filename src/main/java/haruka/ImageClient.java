package haruka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 画图。走 Gemini 原生协议的生图模型（实测用 {@code gemini-3.1-flash-image} 通过）。
 *
 * <p><b>实测契约</b>（不是照文档猜的）：请求体只要 {@code contents}，<b>不要</b>加
 * {@code responseModalities}；返回的 {@code candidates[0].content.parts} 里会混着若干
 * {@code thought:true} 的思考草稿（其中有些也带图！），真正要发出去的是**最后一个不带
 * thought 标记**的 {@code inlineData}。一次调用约 17 秒，1024×1024 JPEG 约 220KB。
 */
public final class ImageClient {

    private static final Log log = Log.get(ImageClient.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 给模型的画图能力说明。刻意由代码提供、而不是让用户写进人设文件 ——
     * 人设文档可以纯写角色，工具协议跟着实现走。
     *
     * <p>{@link ChatService}（真实对话）和控制台"完整链路自测"共用这一份，避免两处措辞走样。
     */
    public static String instruction(int maxPerTurn) {
        return """
                【画图能力】你可以在回复里用 [[draw: 画面描述]] 这个标记来要一张图。
                规则：
                - 画面描述用英文写，只写画面本身（人物、动作、构图、风格），不要写"画一张"这类动词。
                - 想让画面参考某人的头像（比如对方说"根据我的头像画"），写成 [[draw: 描述 | avatar]]；
                  参考本群群头像写 [[draw: 描述 | group]]。不要凭想象编造对方的长相。
                - 标记放在句子里最自然的位置即可，系统会把它替换成图片发出去。
                - 只在对方明确想要图、或你觉得加一张图明显更好时用；纯闲聊不要用。
                - 一轮最多用 %d 次。
                """.formatted(maxPerTurn);
    }

    /** 生成好的图片。 */
    public record Generated(byte[] data, String mimeType) {

        public String extension() {
            String m = mimeType == null ? "" : mimeType.toLowerCase();
            if (m.contains("png")) {
                return "png";
            }
            if (m.contains("webp")) {
                return "webp";
            }
            if (m.contains("gif")) {
                return "gif";
            }
            return "jpg";
        }
    }

    private final Config cfg;

    public ImageClient(Config cfg) {
        this.cfg = cfg;
    }

    /** 把 base_url 和模型名拼成生图端点。 */
    static String endpoint(String baseUrl, String model) {
        return GeminiLlmClient.stripVersionSuffix(baseUrl)
                + "/v1beta/models/" + GeminiLlmClient.bareModel(model) + ":generateContent";
    }

    /**
     * 画一张。
     *
     * @param prompt     画面描述（建议英文，模型对英文提示更稳）
     * @param references 参考图（例如发送者的头像）。有参考图时就是图生图。
     */
    public Generated generate(String prompt, List<Path> references) throws Exception {
        String model = cfg.imageModel == null || cfg.imageModel.isBlank()
                ? "gemini-3.1-flash-image" : cfg.imageModel.trim();
        String url = endpoint(cfg.llmBaseUrl, model);

        String style = cfg.imageStyle == null ? "" : cfg.imageStyle.trim();
        String fullPrompt = style.isEmpty() ? prompt : prompt + "，" + style;

        ObjectNode body = JsonHttp.mapper().createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode user = contents.addObject();
        user.put("role", "user");
        ArrayNode parts = user.putArray("parts");
        // 参考图放在文字前面 —— 图生图就是这个传法。
        if (references != null) {
            for (Path file : references) {
                ObjectNode part = parts.addObject();
                ObjectNode inline = part.putObject("inlineData");
                inline.put("mimeType", JsonHttp.guessMime(file));
                inline.put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
        }
        parts.addObject().put("text", fullPrompt);

        int timeout = Math.max(30, cfg.imageTimeoutSeconds);
        JsonNode root = JsonHttp.postJson(url, headers(), body, timeout, "画图");

        JsonNode feedback = root.path("promptFeedback");
        if (feedback.has("blockReason")) {
            throw new RuntimeException("画图被拒绝: " + feedback.path("blockReason").asText());
        }

        JsonNode resultParts = root.path("candidates").path(0).path("content").path("parts");
        if (!resultParts.isArray() || resultParts.isEmpty()) {
            throw new RuntimeException("画图模型没有返回内容: " + JsonHttp.abbreviate(root.toString()));
        }

        // 优先取最后一个"非思考"的图；全都被标成思考时退回取最后一张图。
        Generated strict = null;
        Generated loose = null;
        for (JsonNode part : resultParts) {
            Generated decoded = decode(part);
            if (decoded == null) {
                continue;
            }
            loose = decoded;
            if (!part.path("thought").asBoolean(false)) {
                strict = decoded;
            }
        }
        Generated result = strict != null ? strict : loose;
        if (result == null) {
            throw new RuntimeException("画图模型只返回了文字，没有图片: "
                    + JsonHttp.abbreviate(resultParts.toString()));
        }
        log.debug("画好一张 {}（{} KB）", result.mimeType(), result.data().length / 1024);
        return result;
    }

    /** 从一个 part 里取图；不是图或解不开就返回 null。 */
    private static Generated decode(JsonNode part) {
        JsonNode inline = part.path("inlineData");
        if (!inline.isObject() || !inline.has("data")) {
            inline = part.path("inline_data"); // 兼容 snake_case 的中转站
        }
        String data = inline.path("data").asText("");
        if (data.isEmpty()) {
            return null;
        }
        String mime = inline.path("mimeType").asText(inline.path("mime_type").asText("image/jpeg"));
        try {
            return new Generated(Base64.getDecoder().decode(data), mime);
        } catch (IllegalArgumentException e) {
            log.warn("画图返回的 base64 解不开，跳过这个 part");
            return null;
        }
    }

    /** 把生成的图片落盘，返回文件路径。和记忆库一样用相对目录，方便整个搬走。 */
    public Path save(Generated generated) throws IOException {
        String configured = cfg.imageSaveDir == null || cfg.imageSaveDir.isBlank()
                ? "data/images" : cfg.imageSaveDir.trim();
        Path dir = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String name = LocalDateTime.now().format(STAMP)
                + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000))
                + "." + generated.extension();
        Path file = dir.resolve(name);
        Files.write(file, generated.data());
        return file;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!cfg.llmApiKey.isBlank()) {
            headers.put("x-goog-api-key", cfg.llmApiKey);
            headers.put("Authorization", "Bearer " + cfg.llmApiKey);
        }
        return headers;
    }
}
