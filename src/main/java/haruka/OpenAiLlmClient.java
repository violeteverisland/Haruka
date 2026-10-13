package haruka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议：{@code POST {base_url}/chat/completions}
 *
 * <p>base_url 写到 {@code /v1} 这一层，例如 {@code https://api.openai.com/v1}，
 * 程序自己拼 {@code /chat/completions}。URL 每次调用现算，改配置立刻生效。
 */
public final class OpenAiLlmClient implements LlmClient {

    private final Config cfg;

    public OpenAiLlmClient(Config cfg) {
        this.cfg = cfg;
    }

    static String chatUrl(String baseUrl) {
        return trimBase(baseUrl) + "/chat/completions";
    }

    static String modelsUrl(String baseUrl) {
        return trimBase(baseUrl) + "/models";
    }

    private static String trimBase(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    @Override
    public String chat(List<ChatMessage> history, String userText, String systemPrompt,
                       List<Path> attachments) throws Exception {
        ObjectNode body = JsonHttp.mapper().createObjectNode();
        body.put("model", cfg.llmModel);
        body.put("temperature", cfg.llmTemperature);
        if (cfg.llmMaxTokens > 0) {
            body.put("max_tokens", cfg.llmMaxTokens);
        }

        ArrayNode messages = body.putArray("messages");
        String system = systemPrompt == null ? "" : systemPrompt;
        if (!system.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
        }
        int from = Math.max(0, history.size() - cfg.llmHistoryLimit);
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            ObjectNode node = messages.addObject();
            node.put("role", m.role());
            node.put("content", m.content());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        if (attachments == null || attachments.isEmpty()) {
            user.put("content", userText);
        } else {
            // 有图时 content 要变成数组形式，纯文本那份会不被识别。
            ArrayNode content = user.putArray("content");
            content.addObject().put("type", "text").put("text", userText);
            for (Path file : attachments) {
                ObjectNode image = content.addObject();
                image.put("type", "image_url");
                image.putObject("image_url").put("url", "data:" + JsonHttp.guessMime(file)
                        + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
        }

        JsonNode root = JsonHttp.postJson(chatUrl(cfg.llmBaseUrl), headers(), body,
                cfg.llmTimeoutSeconds, "OpenAI");

        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new RuntimeException("OpenAI 返回里没有 content: " + JsonHttp.abbreviate(root.toString()));
        }
        return content.asText().trim();
    }

    @Override
    public List<String> listModels() throws Exception {
        return listModels(cfg.llmBaseUrl, cfg.llmApiKey, cfg.llmTimeoutSeconds);
    }

    /**
     * 用显式参数列模型，供控制台「拉取模型列表」用 ——
     * 表单里刚改的 base_url 还没保存也能立刻测。
     */
    static List<String> listModels(String baseUrl, String apiKey, int timeoutSeconds) throws Exception {
        JsonNode root = JsonHttp.getJson(modelsUrl(baseUrl), authHeaders(apiKey), timeoutSeconds, "OpenAI");
        List<String> names = new ArrayList<>();
        // 标准形状是 {"data":[{"id":"..."}]}；个别中转站直接给数组，两种都认。
        JsonNode array = root.isArray() ? root : root.path("data");
        for (JsonNode node : array) {
            String id = node.isTextual() ? node.asText("") : node.path("id").asText("");
            if (!id.isEmpty()) {
                names.add(id);
            }
        }
        return names;
    }

    private Map<String, String> headers() {
        return authHeaders(cfg.llmApiKey);
    }

    private static Map<String, String> authHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }
}
