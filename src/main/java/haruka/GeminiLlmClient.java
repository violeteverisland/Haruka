package haruka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini 原生协议：{@code POST {base}/v1beta/models/{model}:generateContent}
 *
 * <p>llm.base_url 写 {@code http://127.0.0.1:8080} 或者直接贴
 * {@code http://127.0.0.1:8080/v1beta/models} 都行，这里会把多余的
 * {@code /v1beta}、{@code /v1beta/models} 和结尾斜杠去掉再拼。
 *
 * <p>endpoint 不缓存，每次调用现算 —— 控制台改了 base_url 或 model 就能立刻生效。
 */
public final class GeminiLlmClient implements LlmClient {

    /** 列表接口一次最多要这么多；官方默认只有 50。 */
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 5;

    private final Config cfg;

    public GeminiLlmClient(Config cfg) {
        this.cfg = cfg;
    }

    /** 去掉结尾斜杠和已知的版本路径段，留下纯粹的主机+前缀。 */
    static String stripVersionSuffix(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        for (String suffix : new String[]{"/v1beta/models", "/v1beta", "/v1/models", "/v1"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        return base;
    }

    /** 把 base_url 统一成 .../{model}:generateContent。 */
    static String buildEndpoint(String baseUrl, String model) {
        return stripVersionSuffix(baseUrl) + "/v1beta/models/" + bareModel(model) + ":generateContent";
    }

    /** 把 base_url 统一成 .../v1beta/models。 */
    static String modelsUrl(String baseUrl) {
        return stripVersionSuffix(baseUrl) + "/v1beta/models";
    }

    static String bareModel(String model) {
        String m = model == null ? "" : model.trim();
        return m.startsWith("models/") ? m.substring("models/".length()) : m;
    }

    @Override
    public String chat(List<ChatMessage> history, String userText, String systemPrompt,
                       List<Path> attachments) throws Exception {
        ObjectNode body = JsonHttp.mapper().createObjectNode();

        String system = systemPrompt == null ? "" : systemPrompt;
        if (!system.isBlank()) {
            ObjectNode instruction = body.putObject("systemInstruction");
            instruction.putArray("parts").addObject().put("text", system);
        }

        ArrayNode contents = body.putArray("contents");
        int from = Math.max(0, history.size() - cfg.llmHistoryLimit);
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            ObjectNode item = contents.addObject();
            item.put("role", "assistant".equals(m.role()) ? "model" : "user");
            item.putArray("parts").addObject().put("text", m.content());
        }
        ObjectNode user = contents.addObject();
        user.put("role", "user");
        ArrayNode userParts = user.putArray("parts");
        appendImages(userParts, attachments);
        userParts.addObject().put("text", userText);

        ObjectNode generation = body.putObject("generationConfig");
        generation.put("temperature", cfg.llmTemperature);
        if (cfg.llmMaxTokens > 0) {
            generation.put("maxOutputTokens", cfg.llmMaxTokens);
        }

        JsonNode root = JsonHttp.postJson(buildEndpoint(cfg.llmBaseUrl, cfg.llmModel), headers(),
                body, cfg.llmTimeoutSeconds, "Gemini");

        JsonNode feedback = root.path("promptFeedback");
        if (feedback.has("blockReason")) {
            throw new RuntimeException("Gemini 拒绝了这个请求: " + feedback.path("blockReason").asText());
        }

        JsonNode candidate = root.path("candidates").path(0);
        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new RuntimeException("Gemini 返回里没有内容 (finishReason="
                    + candidate.path("finishReason").asText("?") + "): " + JsonHttp.abbreviate(root.toString()));
        }
        // 只取回复正文；thought:true 的思考草稿会被丢掉，否则模型的内部推理会
        // 直接发给用户（实测见过英文的 "Initial assessment: ..." 顶在前面）。
        String text = JsonHttp.joinReplyText(parts);
        if (text.isEmpty()) {
            throw new RuntimeException("Gemini 返回了空文本 (finishReason="
                    + candidate.path("finishReason").asText("?")
                    + "；parts 里只有思考草稿？)");
        }
        return text;
    }

    @Override
    public List<String> listModels() throws Exception {
        return listModels(cfg.llmBaseUrl, cfg.llmApiKey, cfg.llmTimeoutSeconds);
    }

    /**
     * 用显式参数列模型。
     *
     * <p>控制台「拉取模型列表」走这条 —— 用户在表单里刚敲的 provider / base_url / key
     * 还没保存也能立刻测，不必先保存再测（那个来回很容易让人以为功能坏了）。
     */
    static List<String> listModels(String baseUrl, String apiKey, int timeoutSeconds) throws Exception {
        List<String> names = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = modelsUrl(baseUrl) + "?pageSize=" + PAGE_SIZE;
            if (pageToken != null && !pageToken.isEmpty()) {
                url += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
            }
            JsonNode root = JsonHttp.getJson(url, authHeaders(apiKey), timeoutSeconds, "Gemini");
            for (JsonNode node : root.path("models")) {
                String name = bareModel(node.path("name").asText(""));
                if (name.isEmpty()) {
                    continue;
                }
                // 有的中转站不给这个字段，给了就按它过滤掉只支持 embedContent 之类的模型。
                JsonNode methods = node.path("supportedGenerationMethods");
                if (methods.isArray() && !methods.isEmpty() && !containsGenerateContent(methods)) {
                    continue;
                }
                names.add(name);
            }
            pageToken = root.path("nextPageToken").asText("");
            if (pageToken.isEmpty()) {
                break;
            }
        }
        return names;
    }

    /** 把随消息附上的图片塞进 parts。图片在前、文字在后，和官方示例一致。 */
    private static void appendImages(ArrayNode parts, List<Path> attachments) throws IOException {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        for (Path file : attachments) {
            ObjectNode part = parts.addObject();
            ObjectNode inline = part.putObject("inlineData");
            inline.put("mimeType", JsonHttp.guessMime(file));
            inline.put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        }
    }

    private static boolean containsGenerateContent(JsonNode methods) {
        for (JsonNode method : methods) {
            if ("generateContent".equals(method.asText(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 反代的鉴权方式各家不一，两个头一起发，谁认哪个都行。
     */
    private Map<String, String> headers() {
        return authHeaders(cfg.llmApiKey);
    }

    /** 同上，但用显式传入的 key，供控制台"未保存也能测"用。 */
    private static Map<String, String> authHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("x-goog-api-key", apiKey);
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }
}
