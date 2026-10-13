package haruka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 控制接口。用 JDK 自带的 {@link HttpServer} 实现，不引任何新依赖。
 *
 * <p><b>和机器人本体是解耦的</b>：这里只暴露一组 HTTP API，前端是一个完全独立的静态页面
 * （见 {@code webui/index.html}），自己填后端地址和密钥连上来 —— 和 mihomo 的 webui 一个模式。
 * 于是：
 * <ul>
 *   <li>{@code web.enabled=false} 时这个进程是纯机器人，一个 HTTP 端口都不开；</li>
 *   <li>前端可以放在任何地方（本地文件、静态托管）指向这里；</li>
 *   <li>认证走 {@code Authorization: Bearer <web.secret>}，不依赖 cookie，跨源也能用。</li>
 * </ul>
 *
 * <p>配置本身始终以 config.properties 为准：这里的 {@code POST /api/config} 只是"帮你改文件"，
 * 而 {@code POST /api/reload} 和自动重载让手改文件同样能生效。
 */
public final class WebConsole {

    private static final Log log = Log.get(WebConsole.class);
    private static final ObjectMapper MAPPER = JsonHttp.mapper();
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PERSONA_CHARS = 32000;
    /** 覆盖人设前给旧版本留的备份后缀。 */
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String BUILTIN_PAGE = "/webui/index.html";
    /** OneBot 反向 WS 的路径。控制台要把它连同地址一起展示给用户抄。 */
    private static final String WS_PATH = "/onebot/v11/ws";

    /** 改这些键必须重启才生效，其余都是热更新。 */
    private static final Set<String> RESTART_KEYS = Set.of(
            "server.host", "server.port", "bot.workers", "web.host", "web.port", "web.enabled",
            "memory.enabled", "memory.db_path");

    private final Config cfg;
    private final ConfigStore store;
    private final RuntimeStatus status;
    private final LlmClient llm;
    private final MemoryStore memory;
    private final MemoryExtractor extractor;
    private final Persona persona;
    private final boolean webOnly;

    /** 自测里要能真的画一张，所以控制台也持有一个（它只是包着 cfg，无状态）。 */
    private final ImageClient imageClient;
    private final AvatarFetcher avatars;

    private HttpServer server;
    private ExecutorService executor;

    public WebConsole(Config cfg, ConfigStore store, RuntimeStatus status, LlmClient llm,
                      MemoryStore memory, MemoryExtractor extractor, Persona persona, boolean webOnly) {
        this.cfg = cfg;
        this.store = store;
        this.status = status;
        this.llm = llm;
        this.memory = memory;
        this.extractor = extractor;
        this.persona = persona;
        this.webOnly = webOnly;
        this.imageClient = new ImageClient(cfg);
        this.avatars = new AvatarFetcher(cfg);
    }

    public void start() throws IOException {
        String secret = cfg.webSecret == null ? "" : cfg.webSecret;
        if (secret.isBlank() && !isLoopback(cfg.webHost)) {
            throw new IOException("web.secret 为空，但 web.host 是 " + cfg.webHost + "（非本机）。"
                    + "控制接口能读到你的 API Key、也能改写配置，对外监听前请先设置 web.secret。");
        }

        server = HttpServer.create(new InetSocketAddress(cfg.webHost, cfg.webPort), 0);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "haruka-console");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::route);
        server.start();

        log.info("控制接口已启动: http://{}:{}", displayHost(cfg.webHost), cfg.webPort);
        if (secret.isBlank()) {
            log.warn("未设置 web.secret，本机任何人都能访问控制接口；要远程访问必须设置它");
        }
        log.info("控制接口: /api/meta /api/status /api/config /api/config/raw /api/reload");
        log.info("          /api/models /api/llm-urls /api/test-llm /api/logs/history /api/logs/stream");
        log.info("          /api/memories /api/memories/delete /api/memories/export /api/memories/import");
        log.info("          /api/profiles /api/profiles/save /api/profiles/delete");
        Path root = staticRoot();
        if (root != null) {
            log.info("静态页面目录: {}（浏览器打开 http://{}:{} 即可）", root, displayHost(cfg.webHost), cfg.webPort);
        } else if (WebConsole.class.getResource(BUILTIN_PAGE) != null) {
            log.info("静态页面目录不存在，改用 jar 内置的页面；也可以用浏览器直接打开 webui/index.html");
        } else {
            log.info("只提供 API，不提供页面：把 webui/ 放到工作目录，或自行托管 webui/index.html");
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static String displayHost(String host) {
        return host == null || host.isBlank() || "0.0.0.0".equals(host) ? "127.0.0.1" : host;
    }

    private static boolean isLoopback(String host) {
        return host == null || host.isBlank()
                || "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---------------------------------------------------------------- 路由

    private void route(HttpExchange ex) throws IOException {
        // CORS 对每个响应都要有，包括 404 和预检；在 sendResponseHeaders 之前设置都有效。
        cors(ex);

        String path = ex.getRequestURI().getPath();
        try {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!path.startsWith("/api/")) {
                serveStatic(ex, path);
                return;
            }
            switch (path) {
                case "/api/meta" -> guarded(ex, this::handleMeta);
                case "/api/status" -> guarded(ex, this::handleStatus);
                case "/api/config" -> guarded(ex, this::handleConfig);
                case "/api/config/raw" -> guarded(ex, this::handleRawConfig);
                case "/api/persona" -> guarded(ex, this::handlePersona);
                case "/api/reload" -> guarded(ex, this::handleReload);
                case "/api/models" -> guarded(ex, this::handleModels);
                case "/api/llm-urls" -> guarded(ex, this::handleLlmUrls);
                case "/api/test-llm" -> guarded(ex, this::handleTestLlm);
                case "/api/memories" -> guarded(ex, this::handleMemories);
                case "/api/memories/delete" -> guarded(ex, this::handleMemoryDelete);
                case "/api/memories/export" -> guarded(ex, this::handleMemoriesExport);
                case "/api/memories/import" -> guarded(ex, this::handleMemoriesImport);
                case "/api/profiles" -> guarded(ex, this::handleProfiles);
                case "/api/profiles/save" -> guarded(ex, this::handleProfileSave);
                case "/api/profiles/delete" -> guarded(ex, this::handleProfileDelete);
                case "/api/logs/history" -> guarded(ex, this::handleLogHistory);
                case "/api/logs/stream" -> guarded(ex, this::handleLogStream);
                default -> json(ex, 404, err("没有这个接口: " + path));
            }
        } catch (Exception e) {
            log.warn("控制接口处理 {} 出错: {}", path, e.toString());
            // json() 自己吞掉写响应的 IOException，这里不需要再包一层。
            json(ex, 500, err(Log.rootMessage(e)));
        } finally {
            ex.close();
        }
    }

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private void guarded(HttpExchange ex, Handler handler) throws Exception {
        if (!authorized(ex)) {
            json(ex, 401, err("密钥不正确"));
            return;
        }
        handler.handle(ex);
    }

    private static void cors(HttpExchange ex) {
        Headers headers = ex.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        headers.set("Access-Control-Max-Age", "600");
    }

    // ---------------------------------------------------------------- 认证

    /**
     * 密钥为空 = 不校验（启动时已经保证只有监听本机才会走到这里）。
     *
     * <p>除了标准的 Authorization 头，也接受 {@code ?secret=}：{@code EventSource}
     * 没法自定义请求头，SSE 那条流只能靠查询参数传密钥。
     */
    private boolean authorized(HttpExchange ex) {
        String expected = cfg.webSecret == null ? "" : cfg.webSecret;
        if (expected.isEmpty()) {
            return true;
        }
        String presented = bearerToken(ex);
        if (presented == null) {
            presented = queryParam(ex, "secret");
        }
        return presented != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String bearerToken(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    // ---------------------------------------------------------------- 元信息

    private void handleMeta(HttpExchange ex) throws IOException {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("name", "haruka");
        data.put("version", Main.VERSION);
        data.put("configPath", store.path().toString());
        data.put("staticDir", cfg.webStaticDir == null ? "" : cfg.webStaticDir);
        data.put("webOnly", webOnly);
        data.put("authRequired", !isBlank(cfg.webSecret));
        json(ex, 200, ok(data));
    }

    // ---------------------------------------------------------------- 状态

    /**
     * 本机所有可用的局域网 IPv4 地址，用来拼「该填进 SnowLuma 的 ws:// 地址」。
     *
     * <p>省掉用户自己跑 ipconfig 那一步。回环地址不算 —— 填进 SnowLuma 的应该是
     * 局域网地址（同一台机器上用 127.0.0.1 也行，所以两个都给）。
     */
    private static List<String> localIpv4() {
        List<String> out = new java.util.ArrayList<>();
        try {
            for (java.net.NetworkInterface nic
                    : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (java.net.InetAddress address
                        : java.util.Collections.list(nic.getInetAddresses())) {
                    if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        if (ip != null && !ip.isBlank() && !out.contains(ip)) {
                            out.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("枚举本机地址失败: {}", Log.rootMessage(e));
        }
        return out;
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        json(ex, 200, ok(buildStatus()));
    }

    private ObjectNode buildStatus() {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("startedAt", status.startedAt);
        data.put("uptimeSeconds", status.uptimeSeconds());
        data.put("webOnly", status.webOnly);
        data.put("reloadedAt", status.reloadedAt);
        data.put("wsHost", cfg.host);
        data.put("wsPort", cfg.port);
        data.put("oneBotConnected", status.oneBotConnected);
        data.put("selfId", status.selfId);
        data.put("nickname", status.nickname);
        data.put("remoteAddress", status.remoteAddress);
        // 给「连接 QQ」卡片用：把用户要抄进 SnowLuma 的东西直接算好。
        // 连接 QQ 全靠这两项，让他自己去翻配置文件或跑 ipconfig 是没道理的。
        data.put("wsPath", WS_PATH);
        data.put("accessToken", cfg.accessToken == null ? "" : cfg.accessToken);
        data.put("accessTokenSet", !isBlank(cfg.accessToken));
        ArrayNode wsUrls = data.putArray("wsUrls");
        wsUrls.add("ws://127.0.0.1:" + cfg.port + WS_PATH);
        for (String ip : localIpv4()) {
            wsUrls.add("ws://" + ip + ":" + cfg.port + WS_PATH);
        }
        data.put("received", status.received());
        data.put("replied", status.replied());
        data.put("failed", status.failed());
        data.put("lastError", status.lastError);
        data.put("provider", LlmClients.normalizeProvider(cfg.llmProvider));
        data.put("providerLabel", LlmClients.describe(cfg.llmProvider));
        data.put("model", cfg.llmModel);
        data.put("baseUrl", cfg.llmBaseUrl);
        data.put("privateEnabled", cfg.privateEnabled);
        data.put("groupRequireAt", cfg.groupRequireAt);
        data.put("wakeWords", String.join(", ", cfg.wakeWords));
        // 老字段名，兼容旧前端。
        data.put("triggerNames", String.join(", ", cfg.wakeWords));
        data.put("sessionLimit", cfg.llmHistoryLimit);
        data.put("webHost", cfg.webHost);
        data.put("webPort", cfg.webPort);
        data.put("configPath", store.path().toString());

        ObjectNode mem = data.putObject("memory");
        mem.put("enabled", cfg.memoryEnabled);
        mem.put("available", memory != null && memory.available());
        mem.put("dbPath", memory == null ? "" : memory.path().toString());
        mem.put("count", memory == null ? 0 : memory.count());
        // total 是 count 的别名：契约里前端读的是 total。
        mem.put("total", memory == null ? 0 : memory.count());
        mem.put("injectLimit", cfg.memoryInjectLimit);
        mem.put("extractEnabled", cfg.memoryExtractEnabled);
        mem.put("maxPerScope", cfg.memoryMaxPerScope);
        mem.put("minImportance", cfg.memoryMinImportance);

        data.put("persona", persona == null ? "未设置" : persona.describe());
        data.put("personaPath", persona == null || persona.path() == null ? "" : persona.path().toString());
        data.put("imageEnabled", cfg.imageEnabled);
        data.put("imageModel", cfg.imageModel);
        data.put("proactiveEnabled", cfg.proactiveEnabled);
        data.put("avatarEnabled", cfg.avatarEnabled);
        return data;
    }

    // ---------------------------------------------------------------- 配置

    private void handleConfig(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            json(ex, 200, ok(buildConfig()));
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            json(ex, 405, err("只支持 GET / POST"));
            return;
        }

        JsonNode body = readJson(ex);
        if (body == null || !body.isObject()) {
            json(ex, 400, err("请求体不是 JSON 对象"));
            return;
        }

        List<String> problems = new ArrayList<>();
        Map<String, String> changes = collectChanges(body, problems);
        if (!problems.isEmpty()) {
            json(ex, 400, err(String.join("；", problems)));
            return;
        }

        Map<String, String> before = snapshotRestartKeys();
        try {
            store.apply(changes);
        } catch (IOException e) {
            json(ex, 500, err("写入配置文件失败: " + Log.rootMessage(e)));
            return;
        }
        Map<String, String> after = snapshotRestartKeys();

        ArrayNode restart = MAPPER.createArrayNode();
        for (String key : RESTART_KEYS) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                restart.add(key);
            }
        }

        log.info("控制台保存了 {} 项配置{}", changes.size(),
                restart.isEmpty() ? "（已热生效）" : "（有 " + restart.size() + " 项需重启）");

        ObjectNode response = MAPPER.createObjectNode();
        response.put("ok", true);
        response.set("data", buildConfig());
        response.set("restartRequired", restart);
        json(ex, 200, response);
    }

    private void handleReload(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 405, err("只支持 POST"));
            return;
        }
        try {
            long at = store.reload();
            status.reloadedAt = at;
            log.info("按控制台请求重新加载了配置");
            ObjectNode data = MAPPER.createObjectNode();
            data.put("reloadedAt", at);
            json(ex, 200, ok(data));
        } catch (Exception e) {
            json(ex, 200, err("配置文件有错，已保留原配置: " + Log.rootMessage(e)));
        }
    }

    private void handleRawConfig(HttpExchange ex) throws IOException {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("path", store.path().toString());
        data.put("text", store.readText());
        json(ex, 200, ok(data));
    }

    private Map<String, String> snapshotRestartKeys() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("server.host", cfg.host);
        values.put("server.port", String.valueOf(cfg.port));
        values.put("bot.workers", String.valueOf(cfg.workers));
        values.put("web.host", cfg.webHost);
        values.put("web.port", String.valueOf(cfg.webPort));
        values.put("web.enabled", String.valueOf(cfg.webEnabled));
        values.put("memory.enabled", String.valueOf(cfg.memoryEnabled));
        values.put("memory.db_path", cfg.memoryDbPath);
        return values;
    }

    private ObjectNode buildConfig() {
        ObjectNode root = MAPPER.createObjectNode();

        ObjectNode server = root.putObject("server");
        server.put("host", cfg.host);
        server.put("port", cfg.port);
        server.put("access_token", cfg.accessToken);

        ObjectNode llmNode = root.putObject("llm");
        llmNode.put("provider", LlmClients.normalizeProvider(cfg.llmProvider));
        llmNode.put("base_url", cfg.llmBaseUrl);
        // 真实 key 永不下发，只告诉前端"有没有"。
        llmNode.put("api_key", "");
        llmNode.put("api_key_set", !cfg.llmApiKey.isBlank());
        llmNode.put("model", cfg.llmModel);
        llmNode.put("system_prompt", cfg.llmSystemPrompt);
        llmNode.put("system_prompt_file", cfg.llmSystemPromptFile == null ? "" : cfg.llmSystemPromptFile);
        llmNode.put("history_limit", cfg.llmHistoryLimit);
        llmNode.put("temperature", cfg.llmTemperature);
        llmNode.put("max_tokens", cfg.llmMaxTokens);
        llmNode.put("timeout_seconds", cfg.llmTimeoutSeconds);

        ObjectNode bot = root.putObject("bot");
        bot.put("private_enabled", cfg.privateEnabled);
        bot.put("group_require_at", cfg.groupRequireAt);
        ArrayNode wake = bot.putArray("wake_words");
        // trigger_names 是改名前的字段名，继续发一份，老前端不用改也能跑。
        ArrayNode legacyNames = bot.putArray("trigger_names");
        for (String word : cfg.wakeWords) {
            wake.add(word);
            legacyNames.add(word);
        }
        ArrayNode owners = bot.putArray("owner_qq");
        for (String id : cfg.ownerIds) {
            owners.add(id);
        }
        bot.put("owner_title", cfg.ownerTitle);
        bot.put("moderation_enabled", cfg.moderationEnabled);
        bot.put("moderation_prompt", cfg.moderationPrompt == null ? "" : cfg.moderationPrompt);
        bot.put("workers", cfg.workers);
        bot.put("proactive_enabled", cfg.proactiveEnabled);
        bot.put("proactive_on_question", cfg.proactiveOnQuestion);
        bot.put("proactive_cooldown_seconds", cfg.proactiveCooldownSeconds);
        bot.put("proactive_max_per_hour", cfg.proactiveMaxPerHour);

        ObjectNode web = root.putObject("web");
        web.put("enabled", cfg.webEnabled);
        web.put("host", cfg.webHost);
        web.put("port", cfg.webPort);
        web.put("secret", "");
        web.put("secret_set", !isBlank(cfg.webSecret));
        web.put("static_dir", cfg.webStaticDir == null ? "" : cfg.webStaticDir);
        web.put("auth_required", !isBlank(cfg.webSecret));

        ObjectNode memoryNode = root.putObject("memory");
        memoryNode.put("enabled", cfg.memoryEnabled);
        memoryNode.put("db_path", cfg.memoryDbPath);
        memoryNode.put("inject_limit", cfg.memoryInjectLimit);
        memoryNode.put("extract_enabled", cfg.memoryExtractEnabled);
        memoryNode.put("extract_min_chars", cfg.memoryExtractMinChars);
        memoryNode.put("max_per_scope", cfg.memoryMaxPerScope);
        memoryNode.put("min_importance", cfg.memoryMinImportance);
        memoryNode.put("remember_keywords", cfg.memoryRememberKeywords == null ? "" : cfg.memoryRememberKeywords);
        memoryNode.put("available", memory != null && memory.available());

        ObjectNode imageNode = root.putObject("image");
        imageNode.put("enabled", cfg.imageEnabled);
        imageNode.put("model", cfg.imageModel);
        imageNode.put("save_dir", cfg.imageSaveDir);
        imageNode.put("style", cfg.imageStyle);
        imageNode.put("send_mode", cfg.imageSendMode);
        imageNode.put("timeout_seconds", cfg.imageTimeoutSeconds);
        imageNode.put("max_per_turn", cfg.imageMaxPerTurn);

        ObjectNode avatar = root.putObject("avatar");
        avatar.put("enabled", cfg.avatarEnabled);
        avatar.put("look_keywords", cfg.avatarLookKeywords == null ? "" : cfg.avatarLookKeywords);
        avatar.put("size", cfg.avatarSize);
        avatar.put("cache_minutes", cfg.avatarCacheMinutes);
        avatar.put("cache_dir", cfg.avatarCacheDir);
        avatar.put("timeout_seconds", cfg.avatarTimeoutSeconds);

        return root;
    }

    private Map<String, String> collectChanges(JsonNode body, List<String> problems) {
        Map<String, String> changes = new LinkedHashMap<>();

        JsonNode server = body.path("server");
        if (server.isObject()) {
            putText(changes, "server.host", server, "host");
            putInt(changes, problems, "server.port", server, "port", 1, 65535);
            String token = text(server, "access_token");
            if (token != null) {
                if (token.isBlank()) {
                    problems.add("server.access_token 不能为空，否则 SnowLuma 连不上");
                } else {
                    changes.put("server.access_token", token);
                }
            }
        }

        JsonNode llmNode = body.path("llm");
        if (llmNode.isObject()) {
            String provider = text(llmNode, "provider");
            if (provider != null && !provider.isBlank()) {
                try {
                    changes.put("llm.provider", LlmClients.normalizeProvider(provider));
                } catch (IllegalArgumentException e) {
                    problems.add(e.getMessage());
                }
            }
            putText(changes, "llm.base_url", llmNode, "base_url");
            String key = text(llmNode, "api_key");
            // 留空表示"不修改"，这样页面不需要把真实 key 拿回来再提交。
            if (key != null && !key.isBlank()) {
                changes.put("llm.api_key", key);
            }
            putText(changes, "llm.model", llmNode, "model");
            putText(changes, "llm.system_prompt_file", llmNode, "system_prompt_file");
            String prompt = text(llmNode, "system_prompt");
            if (prompt != null) {
                changes.put("llm.system_prompt", prompt);
            }
            putInt(changes, problems, "llm.history_limit", llmNode, "history_limit", 1, 1000);
            putDouble(changes, problems, "llm.temperature", llmNode, "temperature", 0, 2);
            putInt(changes, problems, "llm.max_tokens", llmNode, "max_tokens", 0, 1_000_000);
            putInt(changes, problems, "llm.timeout_seconds", llmNode, "timeout_seconds", 1, 3600);
        }

        JsonNode bot = body.path("bot");
        if (bot.isObject()) {
            if (bot.has("private_enabled")) {
                changes.put("bot.private_enabled", String.valueOf(bot.path("private_enabled").asBoolean()));
            }
            if (bot.has("group_require_at")) {
                changes.put("bot.group_require_at", String.valueOf(bot.path("group_require_at").asBoolean()));
            }
            JsonNode words = bot.path("wake_words");
            if (!words.isArray() && !words.isTextual()) {
                words = bot.path("trigger_names"); // 兼容旧前端
            }
            if (words.isArray() || words.isTextual()) {
                List<String> list = new ArrayList<>();
                if (words.isArray()) {
                    for (JsonNode node : words) {
                        String value = node.asText("").trim();
                        if (!value.isEmpty()) {
                            list.add(value);
                        }
                    }
                } else {
                    for (String part : words.asText("").split("[,，]")) {
                        String value = part.trim();
                        if (!value.isEmpty()) {
                            list.add(value);
                        }
                    }
                }
                changes.put("bot.wake_words", String.join(",", list));
            }
            JsonNode ownerNode = bot.path("owner_qq");
            if (ownerNode.isArray() || ownerNode.isTextual()) {
                List<String> owners = new ArrayList<>();
                if (ownerNode.isArray()) {
                    for (JsonNode node : ownerNode) {
                        String value = node.asText("").trim();
                        if (!value.isEmpty()) {
                            owners.add(value);
                        }
                    }
                } else {
                    for (String part : ownerNode.asText("").split("[,，]")) {
                        String value = part.trim();
                        if (!value.isEmpty()) {
                            owners.add(value);
                        }
                    }
                }
                changes.put("bot.owner_qq", String.join(",", owners));
            }
            putText(changes, "bot.owner_title", bot, "owner_title");
            if (bot.has("moderation_enabled")) {
                changes.put("bot.moderation_enabled", String.valueOf(bot.path("moderation_enabled").asBoolean()));
            }
            putText(changes, "bot.moderation_prompt", bot, "moderation_prompt");
            putInt(changes, problems, "bot.workers", bot, "workers", 1, 64);
            if (bot.has("proactive_enabled")) {
                changes.put("bot.proactive_enabled", String.valueOf(bot.path("proactive_enabled").asBoolean()));
            }
            if (bot.has("proactive_on_question")) {
                changes.put("bot.proactive_on_question", String.valueOf(bot.path("proactive_on_question").asBoolean()));
            }
            putInt(changes, problems, "bot.proactive_cooldown_seconds", bot, "proactive_cooldown_seconds", 0, 3600);
            putInt(changes, problems, "bot.proactive_max_per_hour", bot, "proactive_max_per_hour", 1, 120);
        }

        JsonNode web = body.path("web");
        if (web.isObject()) {
            putText(changes, "web.host", web, "host");
            putInt(changes, problems, "web.port", web, "port", 1, 65535);
            String secret = text(web, "secret");
            if (secret == null || secret.isBlank()) {
                // 兼容旧字段名
                secret = text(web, "password");
            }
            if (secret != null && !secret.isBlank()) {
                changes.put("web.secret", secret);
            }
            putText(changes, "web.static_dir", web, "static_dir");
            if (web.has("enabled")) {
                changes.put("web.enabled", String.valueOf(web.path("enabled").asBoolean()));
            }
        }

        JsonNode memoryNode = body.path("memory");
        if (memoryNode.isObject()) {
            if (memoryNode.has("enabled")) {
                changes.put("memory.enabled", String.valueOf(memoryNode.path("enabled").asBoolean()));
            }
            putText(changes, "memory.db_path", memoryNode, "db_path");
            putInt(changes, problems, "memory.inject_limit", memoryNode, "inject_limit", 0, 100);
            if (memoryNode.has("extract_enabled")) {
                changes.put("memory.extract_enabled",
                        String.valueOf(memoryNode.path("extract_enabled").asBoolean()));
            }
            putInt(changes, problems, "memory.extract_min_chars", memoryNode, "extract_min_chars", 0, 1000);
            putInt(changes, problems, "memory.max_per_scope", memoryNode, "max_per_scope", 1, 100000);
            putDouble(changes, problems, "memory.min_importance", memoryNode, "min_importance", 0, 1);
            putText(changes, "memory.remember_keywords", memoryNode, "remember_keywords");
        }

        JsonNode imageNode = body.path("image");
        if (imageNode.isObject()) {
            if (imageNode.has("enabled")) {
                changes.put("image.enabled", String.valueOf(imageNode.path("enabled").asBoolean()));
            }
            putText(changes, "image.model", imageNode, "model");
            putText(changes, "image.save_dir", imageNode, "save_dir");
            String style = text(imageNode, "style");
            if (style != null) {
                changes.put("image.style", style);
            }
            String mode = text(imageNode, "send_mode");
            if (mode != null && !mode.isBlank()) {
                String normalized = mode.trim().toLowerCase();
                if ("file".equals(normalized) || "base64".equals(normalized)) {
                    changes.put("image.send_mode", normalized);
                } else {
                    problems.add("image.send_mode 只能是 file 或 base64");
                }
            }
            putInt(changes, problems, "image.timeout_seconds", imageNode, "timeout_seconds", 10, 600);
            putInt(changes, problems, "image.max_per_turn", imageNode, "max_per_turn", 1, 10);
        }

        JsonNode avatarNode = body.path("avatar");
        if (avatarNode.isObject()) {
            if (avatarNode.has("enabled")) {
                changes.put("avatar.enabled", String.valueOf(avatarNode.path("enabled").asBoolean()));
            }
            putText(changes, "avatar.look_keywords", avatarNode, "look_keywords");
            putInt(changes, problems, "avatar.size", avatarNode, "size", 40, 640);
            putInt(changes, problems, "avatar.cache_minutes", avatarNode, "cache_minutes", 0, 10080);
            putText(changes, "avatar.cache_dir", avatarNode, "cache_dir");
            putInt(changes, problems, "avatar.timeout_seconds", avatarNode, "timeout_seconds", 5, 120);
        }

        // 对外监听却没有密钥，等于把控制接口开放给整个网络。
        String effectiveHost = changes.getOrDefault("web.host", cfg.webHost);
        String effectiveSecret = changes.getOrDefault("web.secret", cfg.webSecret);
        if (isBlank(effectiveSecret) && !isLoopback(effectiveHost)) {
            problems.add("web.host 改成非本机地址时必须同时设置 web.secret"
                    + "（控制接口能读到你的 API Key、也能改写配置）");
        }

        return changes;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? null : node.asText("");
    }

    private static void putText(Map<String, String> changes, String key, JsonNode parent, String field) {
        String value = text(parent, field);
        if (value != null) {
            changes.put(key, value.trim());
        }
    }

    private static void putInt(Map<String, String> changes, List<String> problems, String key,
                               JsonNode parent, String field, int min, int max) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return;
        }
        int value = node.asInt(Integer.MIN_VALUE);
        if (value == Integer.MIN_VALUE || value < min || value > max) {
            problems.add(key + " 需要在 " + min + " 到 " + max + " 之间");
            return;
        }
        changes.put(key, String.valueOf(value));
    }

    private static void putDouble(Map<String, String> changes, List<String> problems, String key,
                                  JsonNode parent, String field, double min, double max) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return;
        }
        double value = node.asDouble(Double.NaN);
        if (Double.isNaN(value) || value < min || value > max) {
            problems.add(key + " 需要在 " + min + " 到 " + max + " 之间");
            return;
        }
        changes.put(key, String.valueOf(value));
    }

    // ---------------------------------------------------------------- 模型

    /**
     * 列模型。
     *
     * <p>支持 POST 带覆盖值 {@code {provider, base_url, api_key}} —— 用户在表单里刚敲的
     * 值可以不保存就测。空字符串表示"这一项不改"，沿用已保存的配置（和 api_key 的约定一致）。
     */
    private void handleModels(HttpExchange ex) {
        JsonNode body = readBodyQuietly(ex);
        String provider = LlmClients.normalizeProvider(
                override(body, "provider", cfg.llmProvider));
        String baseUrl = override(body, "base_url", cfg.llmBaseUrl);
        String apiKey = override(body, "api_key", cfg.llmApiKey);

        boolean gemini = "gemini".equals(provider);
        String requestUrl = gemini ? GeminiLlmClient.modelsUrl(baseUrl) : OpenAiLlmClient.modelsUrl(baseUrl);
        try {
            List<String> models = gemini
                    ? GeminiLlmClient.listModels(baseUrl, apiKey, cfg.llmTimeoutSeconds)
                    : OpenAiLlmClient.listModels(baseUrl, apiKey, cfg.llmTimeoutSeconds);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("provider", provider);
            data.put("baseUrl", baseUrl);
            data.put("requestUrl", requestUrl);
            ArrayNode array = data.putArray("models");
            for (String model : models) {
                array.add(model);
            }
            json(ex, 200, ok(data));
        } catch (Exception e) {
            String detail = Log.rootMessage(e);
            // 用户点了按钮就必须知道为什么失败：既进日志面板，也进响应体。
            log.warn("拉取模型列表失败（provider={} base_url={}）: {}（实际请求 {}）",
                    provider, baseUrl, detail, requestUrl);
            json(ex, 200, err("拉取模型列表失败： " + detail
                    + "\n实际请求的是 " + requestUrl
                    + "\n请照着上面的「实际请求」核对 base_url 该填到哪一层，以及 api_key 是否正确。"));
        }
    }

    /**
     * 把 provider / base_url / model 拼成真实的请求地址，给控制台做「实际请求」预览。
     *
     * <p>刻意放在后端算：归一化规则只有一份。前端要是自己复制一份，两处迟早不一致，
     * 而用户正是照着这个预览去填 base_url 的 —— 预览错了比没有预览更坏。
     */
    private void handleLlmUrls(HttpExchange ex) {
        JsonNode body = readBodyQuietly(ex);
        String provider = LlmClients.normalizeProvider(override(body, "provider", cfg.llmProvider));
        // base_url 和 model 取字面值，**不回落到已保存的配置** ——
        // 预览的意义就是"照表单现在填的算出来给你看"，回落会让用户看到
        // 一个他没填过的地址，比没有预览更坏。
        String baseUrl = literal(body, "base_url");
        String model = literal(body, "model");

        ObjectNode data = MAPPER.createObjectNode();
        data.put("provider", provider);
        data.put("baseUrl", baseUrl);
        if (baseUrl.isBlank()) {
            // 没填就拼不出来，别编一个假的地址骗用户。
            data.put("chatUrl", "");
            data.put("modelsUrl", "");
            data.put("modelPlaceholder", false);
        } else if ("gemini".equals(provider)) {
            boolean placeholder = model.isBlank();
            data.put("chatUrl", placeholder
                    ? GeminiLlmClient.modelsUrl(baseUrl) + "/<model>:generateContent"
                    : GeminiLlmClient.buildEndpoint(baseUrl, model));
            data.put("modelsUrl", GeminiLlmClient.modelsUrl(baseUrl));
            // 明确告诉前端"这个地址里的 model 是占位符"，
            // 别让它去猜字符串形态 —— 改一次占位写法前端就失效了。
            data.put("modelPlaceholder", placeholder);
        } else {
            data.put("chatUrl", OpenAiLlmClient.chatUrl(baseUrl));
            data.put("modelsUrl", OpenAiLlmClient.modelsUrl(baseUrl));
            // OpenAI 协议下 model 在请求体里，不在 URL 上，所以永远不算占位。
            data.put("modelPlaceholder", false);
        }
        json(ex, 200, ok(data));
    }

    /** 取请求体；取不到（GET、空体、格式不对）就返回 null，由调用方回落到已保存的配置。 */
    private JsonNode readBodyQuietly(HttpExchange ex) {
        try {
            return readJson(ex);
        } catch (Exception e) {
            log.debug("读请求体失败，改用已保存的配置: {}", Log.rootMessage(e));
            return null;
        }
    }

    /** 覆盖值优先；缺失或空串表示"这一项不改"，用 fallback。 */
    private static String override(JsonNode body, String key, String fallback) {
        if (body == null || !body.hasNonNull(key)) {
            return fallback;
        }
        String value = body.path(key).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    /** 取字面值：空就是空，不回落到任何默认值。用于"照表单算"的预览类接口。 */
    private static String literal(JsonNode body, String key) {
        return body == null ? "" : body.path(key).asText("").trim();
    }

    /**
     * 试一次模型。
     *
     * <p>请求体 {@code {"text":"...","scope":"...","sender":"...","extract":true,"image":true}}。
     * 给了 {@code scope} 就走"记忆注入 → 回复 →（可选）抽取落库"的完整链路；
     * {@code image} 为真时还会带上画图能力说明，把模型点单的 {@code [[draw: ...]]} 真的画出来。
     * 这样不经过 QQ 也能验证长期记忆和画图是不是都在工作。
     */
    private void handleTestLlm(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        String text = body == null ? "" : body.path("text").asText("");
        if (text.isBlank()) {
            text = "你好";
        }
        String scope = body == null ? "" : body.path("scope").asText("").trim();
        String sender = body == null ? "" : body.path("sender").asText("").trim();
        String senderId = body == null ? "" : body.path("senderId").asText("").trim();
        boolean extract = body != null && body.path("extract").asBoolean(false);
        boolean wantImage = body != null && body.path("image").asBoolean(false);

        long started = System.currentTimeMillis();
        try {
            // 自测也走和应用一样的路径：查画像（只读，不自测也涨消息数），记忆按人+会话取。
            MemoryStore.Profile speaker = memory == null ? null : memory.profile(senderId);
            List<String> scopes = new ArrayList<>();
            if (!senderId.isEmpty()) {
                scopes.add("person:" + senderId);
            }
            if (!scope.isEmpty()) {
                scopes.add(scope);
            }
            scopes.add("global");
            MemoryPrompt.Block block = MemoryPrompt.build(cfg, memory, scopes);

            boolean drawing = wantImage && cfg.imageEnabled;
            // 身份与发言尺度放在记忆前面，和真实聊天链路保持同样的顺序。
            StringBuilder head = new StringBuilder();
            for (String b : new String[] {
                    People.block(cfg, speaker, senderId),
                    Relation.safetyBlock(cfg, speaker),
                    Relation.confessionHint(cfg, speaker) }) {
                if (!b.isEmpty()) {
                    if (head.length() > 0) {
                        head.append('\n');
                    }
                    head.append(b);
                }
            }
            String extra = head.toString();
            String memoryBlock = block.text();
            if (!memoryBlock.isEmpty()) {
                extra = extra.isEmpty() ? memoryBlock : extra + "\n" + memoryBlock;
            }
            if (drawing) {
                extra = LlmClients.composeSystem(extra, ImageClient.instruction(cfg.imageMaxPerTurn));
            }

            // 自测也要带上真实人设，否则测出来的语气跟实际聊天不一样。
            String system = persona == null
                    ? LlmClients.composeSystem(cfg.llmSystemPrompt, extra)
                    : persona.compose(extra);
            // 顺带验一下"看头像"：消息里提到头像时也把头像附上。
            List<Path> attachments = attachmentsFor(senderId, text);
            // 自测也走同一条路径：该加的主人标记就得加，否则测出来的语气和应用不一致。
            text = Owner.mark(cfg, senderId, text);
            String raw = llm.chat(List.of(), text, system, attachments);
            long replyMs = System.currentTimeMillis() - started;

            List<ChatService.DrawRequest> draws = new ArrayList<>();
            String reply = drawing ? ChatService.extractDraws(raw, cfg.imageMaxPerTurn, draws) : raw;

            int remembered = 0;
            if (extract && !scope.isEmpty() && extractor != null) {
                remembered = extractor.extractNow(scope, sender, text, raw);
            }

            ObjectNode data = MAPPER.createObjectNode();
            data.put("reply", reply);
            data.put("elapsedMs", replyMs);
            data.put("totalMs", System.currentTimeMillis() - started);
            data.put("provider", LlmClients.normalizeProvider(cfg.llmProvider));
            data.put("model", cfg.llmModel);
            data.put("scope", scope);
            data.put("remembered", remembered);
            ArrayNode injected = data.putArray("injected");
            for (MemoryStore.Memory item : block.items()) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("id", item.id());
                node.put("content", item.content());
                injected.add(node);
            }
            ArrayNode attached = data.putArray("attached");
            for (Path file : attachments) {
                attached.add(file.getFileName().toString());
            }

            // 要图就把图也真画出来：这样画图链路不必等一条 QQ 消息才能验证。
            ArrayNode images = data.putArray("images");
            for (ChatService.DrawRequest request : draws) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("prompt", request.prompt());
                node.put("source", request.source());
                try {
                    List<Path> references = testReferences(request.source(), scope, senderId);
                    ImageClient.Generated generated = imageClient.generate(request.prompt(), references);
                    Path file = imageClient.save(generated);
                    node.put("ok", true);
                    node.put("file", file.toString());
                    node.put("mimeType", generated.mimeType());
                    node.put("bytes", generated.data().length);
                    node.put("references", references.size());
                } catch (Exception e) {
                    node.put("ok", false);
                    node.put("error", Log.rootMessage(e));
                }
                images.add(node);
            }
            data.put("totalMs", System.currentTimeMillis() - started);
            json(ex, 200, ok(data));
        } catch (Exception e) {
            json(ex, 200, err(Log.rootMessage(e)));
        }
    }

    // ---------------------------------------------------------------- 日志

    private void handleLogHistory(HttpExchange ex) throws IOException {
        long after = longParam(ex, "after", 0);
        List<Log.Entry> entries = after > 0 ? Log.snapshotAfter(after) : Log.snapshot();
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode array = data.putArray("entries");
        for (Log.Entry entry : entries) {
            array.add(entryNode(entry));
        }
        data.put("latestSeq", Log.latestSeq());
        json(ex, 200, ok(data));
    }

    private void handleLogStream(HttpExchange ex) throws Exception {
        long after = longParam(ex, "after", 0);

        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        Object writeLock = new Object();
        // 用 maxSent 去重：先补历史再订阅，两边都经过这里，保证同一 seq 只发一次。
        AtomicLong maxSent = new AtomicLong(after);

        Runnable unsubscribe = Log.subscribe(entry -> {
            synchronized (writeLock) {
                if (entry.seq() <= maxSent.get()) {
                    return;
                }
                try {
                    writeEvent(out, entry);
                    maxSent.set(entry.seq());
                } catch (IOException e) {
                    // 客户端走了；心跳循环会注意到并收尾
                }
            }
        });

        try {
            synchronized (writeLock) {
                out.write("retry: 3000\n\n".getBytes(StandardCharsets.UTF_8));
                for (Log.Entry entry : (after > 0 ? Log.snapshotAfter(after) : Log.snapshot())) {
                    if (entry.seq() <= maxSent.get()) {
                        continue;
                    }
                    writeEvent(out, entry);
                    maxSent.set(entry.seq());
                }
                out.flush();
            }
            while (true) {
                Thread.sleep(15000);
                synchronized (writeLock) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 连接断开，正常收尾
        } finally {
            unsubscribe.run();
        }
    }

    private static void writeEvent(OutputStream out, Log.Entry entry) throws IOException {
        StringBuilder sb = new StringBuilder(entry.message().length() + 96);
        sb.append("data: ").append(entryNode(entry)).append("\n\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static ObjectNode entryNode(Log.Entry entry) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("seq", entry.seq());
        node.put("time", entry.time());
        node.put("level", entry.level().name());
        node.put("name", entry.name());
        node.put("message", entry.message());
        return node;
    }

    // ---------------------------------------------------------------- 静态页面

    /** 静态目录按当前配置解析；目录不存在或路径非法就返回 null（表示"没有页面可提供"）。 */
    private Path staticRoot() {
        String dir = cfg.webStaticDir == null ? "" : cfg.webStaticDir.trim();
        if (dir.isEmpty()) {
            return null;
        }
        try {
            Path candidate = Path.of(dir).toAbsolutePath().normalize();
            return Files.isDirectory(candidate) ? candidate : null;
        } catch (RuntimeException e) {
            // 例如在 Windows 上填了非法字符
            return null;
        }
    }

    private void serveStatic(HttpExchange ex, String requestPath) throws IOException {
        String relative = requestPath.equals("/") ? "index.html" : requestPath.substring(1);

        Path root = staticRoot();
        if (root != null) {
            Path target = root.resolve(relative).normalize();
            // 防目录穿越：规范化之后必须仍在静态根目录内。
            if (!target.startsWith(root)) {
                json(ex, 403, err("路径越界"));
                return;
            }
            if (Files.isRegularFile(target)) {
                sendFile(ex, Files.readAllBytes(target), contentType(target.getFileName().toString()));
                return;
            }
        }

        if (relative.equals("index.html")) {
            // 目录里没有页面时退回 jar 内置的那份，保证"只丢一个 jar 也能打开"。
            try (InputStream in = WebConsole.class.getResourceAsStream(BUILTIN_PAGE)) {
                if (in != null) {
                    sendFile(ex, in.readAllBytes(), "text/html");
                    return;
                }
            }
            serveNotice(ex);
            return;
        }

        json(ex, 404, err("找不到: " + relative));
    }

    private void sendFile(HttpExchange ex, byte[] bytes, String contentType) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 没有页面可提供时，返回一张说明页，顺便把接口和地址列出来。 */
    private void serveNotice(HttpExchange ex) throws IOException {
        String origin = "http://" + displayHost(cfg.webHost) + ":" + cfg.webPort;
        String html = """
                <!doctype html>
                <html lang="zh-CN"><head><meta charset="utf-8">
                <title>haruka 控制接口</title>
                <style>
                  body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;max-width:44rem;
                       margin:3rem auto;padding:0 1.5rem;line-height:1.7;color:#1f2933}
                  code{background:#f0f4f8;padding:.15rem .4rem;border-radius:.25rem;
                       font-family:ui-monospace,Consolas,monospace}
                  h1{font-size:1.5rem}
                  li{margin:.25rem 0}
                </style></head><body>
                <h1>haruka 控制接口在运行</h1>
                <p>这个后端只提供 HTTP API，没有内置页面 —— 前端是独立的静态文件。</p>
                <p>把 haruka 的 <code>webui/</code> 目录放到工作目录下（或在配置里设置
                <code>web.static_dir</code> 指向它），刷新本页即可；也可以直接用浏览器打开
                <code>webui/index.html</code>，在后端地址里填 <code>%s</code>。</p>
                <p>可用的接口：</p>
                <ul>
                  <li><code>GET /api/meta</code> 版本与配置路径</li>
                  <li><code>GET /api/status</code> 运行状态</li>
                  <li><code>GET|POST /api/config</code> 读取 / 保存配置</li>
                  <li><code>POST /api/reload</code> 从磁盘重新加载配置</li>
                  <li><code>GET /api/models</code> 拉取模型列表</li>
                  <li><code>POST /api/test-llm</code> 试一次模型</li>
                  <li><code>GET /api/logs/history</code>、<code>GET /api/logs/stream</code> 日志</li>
                </ul>
                <p>需要密钥时请带上 <code>Authorization: Bearer &lt;web.secret&gt;</code>。</p>
                </body></html>
                """.formatted(origin);
        sendFile(ex, html.getBytes(StandardCharsets.UTF_8), "text/html");
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "text/javascript";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- 人设

    /**
     * GET 读人设文件，POST 写。人设是写作体验最好的一块，所以给它单独一个接口和一个大文本框，
     * 而不是塞进 properties 的单行值里。保存后 {@link ConfigWatcher} 也会认出来（热生效）。
     */
    private void handlePersona(HttpExchange ex) throws Exception {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 200, ok(personaNode()));
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 405, err("只支持 GET / POST"));
            return;
        }
        if (persona == null) {
            json(ex, 200, err("人设不可用"));
            return;
        }

        JsonNode body = readJson(ex);
        String text = body == null ? "" : body.path("text").asText("");
        if (text.length() > MAX_PERSONA_CHARS) {
            json(ex, 400, err("人设太长了（上限 " + MAX_PERSONA_CHARS + " 字，当前 " + text.length() + "）"));
            return;
        }

        // 优先级规则：以控制台为准 —— 文件在别处被改过也照存，不打断操作。
        // 但先留一份带时间戳的备份，否则外面那版改动会无声消失。
        long expectedMtime = body == null ? -1 : body.path("expectedMtime").asLong(-1);
        Path current = persona.path();
        boolean overwritten = expectedMtime > 0 && current != null && mtimeOf(current) != expectedMtime;
        // 还有一种值得备份的情况：把有内容的人设存成空白。多半是误操作（文本框被清空、
        // 或者调用方传了个空 body），留一份就还能救回来。
        boolean suspiciousWipe = text.isBlank() && current != null && sizeOf(current) > 0;
        String backup = "";
        if (overwritten || suspiciousWipe) {
            try {
                // 加随机后缀：同一秒内备份两次也不会互相覆盖。
                Path backupPath = current.resolveSibling(
                        current.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_STAMP)
                                + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)));
                Files.copy(current, backupPath, StandardCopyOption.REPLACE_EXISTING);
                backup = backupPath.toString();
                log.warn("人设保存前留了备份 {}（原因: {}）", backupPath.getFileName(),
                        overwritten ? "文件在外部被改过" : "把有内容的人设存成了空白");
            } catch (IOException e) {
                log.warn("备份被覆盖的人设失败: {}", Log.rootMessage(e));
            }
        }

        Path target = persona.path();
        if (target == null) {
            String configured = cfg.llmSystemPromptFile;
            if (configured == null || configured.isBlank()) {
                json(ex, 400, err("还没配置人设文件路径，先在下面的配置里填 llm.system_prompt_file"));
                return;
            }
            try {
                target = Path.of(configured.trim()).toAbsolutePath().normalize();
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (Exception e) {
                json(ex, 400, err("人设文件路径不合法: " + Log.rootMessage(e)));
                return;
            }
        }

        try {
            Files.writeString(target, text, StandardCharsets.UTF_8);
            persona.invalidate();
            log.info("控制台保存了人设 {}（{} 字）", target.getFileName(), text.length());
            // 直接回完整对象（含新的 mtime），页面就不用再 GET 一次来刷新摘要。
            ObjectNode data = personaNode();
            data.put("savedAt", System.currentTimeMillis());
            data.put("backup", backup);
            json(ex, 200, ok(data));
        } catch (IOException e) {
            json(ex, 200, err("写人设文件失败: " + Log.rootMessage(e)));
        }
    }

    /** 人设的当前状态。GET 和 POST 共用，保证两边的 summary/字数口径一致。 */
    private ObjectNode personaNode() {
        Path resolved = persona == null ? null : persona.path();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("configuredPath", cfg.llmSystemPromptFile == null ? "" : cfg.llmSystemPromptFile);
        data.put("resolvedPath", resolved == null ? "" : resolved.toString());
        data.put("exists", resolved != null);
        data.put("text", resolved == null ? "" : persona.fileText());
        data.put("inlinePrompt", cfg.llmSystemPrompt);
        data.put("summary", persona == null ? "未设置" : persona.describe());
        data.put("mtime", resolved == null ? 0 : mtimeOf(resolved));
        return data;
    }

    private static long mtimeOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 自测里的"看头像"：和 ChatService 用同一份关键词配置。 */
    private List<Path> attachmentsFor(String senderId, String text) {
        if (!cfg.avatarEnabled || senderId.isEmpty() || text == null) {
            return List.of();
        }
        String keywords = cfg.avatarLookKeywords == null ? "" : cfg.avatarLookKeywords;
        boolean mentioned = false;
        for (String keyword : keywords.split("[,，]")) {
            String k = keyword.trim();
            if (!k.isEmpty() && text.contains(k)) {
                mentioned = true;
                break;
            }
        }
        if (!mentioned) {
            return List.of();
        }
        Path avatar = avatars.member(senderId);
        return avatar == null ? List.of() : List.of(avatar);
    }

    /** 自测里把 [[draw: ... | source]] 的来源换成真图。 */
    private List<Path> testReferences(String source, String scope, String senderId) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Path picked = switch (source) {
            case "avatar", "sender", "me", "user" -> senderId.isEmpty() ? null : avatars.member(senderId);
            case "group", "group_avatar" -> {
                String groupId = scope != null && scope.startsWith("group:") ? scope.substring(6) : "";
                yield groupId.isEmpty() ? null : avatars.group(groupId);
            }
            default -> null;
        };
        return picked == null ? List.of() : List.of(picked);
    }

    // ---------------------------------------------------------------- 人物画像

    private void handleProfiles(HttpExchange ex) throws IOException {
        if (memory == null || !memory.available()) {
            json(ex, 200, err("记忆库不可用 —— 画像和它存在同一个 .db 里"));
            return;
        }
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode array = data.putArray("profiles");
        for (MemoryStore.Profile p : memory.listProfiles(0)) {
            array.add(profileNode(p));
        }
        data.put("total", array.size());
        json(ex, 200, ok(data));
    }

    private void handleProfileSave(HttpExchange ex) throws IOException {
        if (memory == null || !memory.available()) {
            json(ex, 200, err("记忆库不可用 —— 画像和它存在同一个 .db 里"));
            return;
        }
        JsonNode body = readJson(ex);
        String qq = body == null ? "" : body.path("qq").asText("").trim();
        if (qq.isEmpty()) {
            json(ex, 200, err("qq 不能为空"));
            return;
        }
        if (!qq.chars().allMatch(Character::isDigit)) {
            // QQ 号是画像的主键，也是"解除审查"唯一认的东西，填错等于没填。
            json(ex, 200, err("qq 只能是数字"));
            return;
        }
        int affection = Math.max(0, Math.min(100, body.path("affection").asInt(0)));
        boolean spouse = body.path("spouse").asBoolean(false);
        if (!memory.saveProfile(qq, body.path("name").asText(""), affection, spouse,
                body.path("note").asText(""))) {
            json(ex, 200, err("保存失败"));
            return;
        }
        MemoryStore.Profile saved = memory.profile(qq);
        // 婚姻状态写进长期记忆（幂等：写过的不会再写）。
        if (spouse && Relation.ensureMarriageMemory(saved, memory)) {
            log.info("[关系] 结婚已写入长期记忆（QQ {}）", qq);
        }
        ObjectNode data = MAPPER.createObjectNode();
        data.set("profile", profileNode(saved));
        json(ex, 200, ok(data));
    }

    private void handleProfileDelete(HttpExchange ex) throws IOException {
        if (memory == null || !memory.available()) {
            json(ex, 200, err("记忆库不可用 —— 画像和它存在同一个 .db 里"));
            return;
        }
        JsonNode body = readJson(ex);
        String qq = body == null ? "" : body.path("qq").asText("").trim();
        if (qq.isEmpty()) {
            json(ex, 200, err("qq 不能为空"));
            return;
        }
        boolean gone = memory.deleteProfile(qq);
        // 记忆不跟着删：记忆归她，要删去「记忆」页删。
        log.info("删除画像: {}（{}）", qq, gone ? "成功" : "本来就不存在");
        ObjectNode data = MAPPER.createObjectNode();
        data.put("deleted", gone);
        json(ex, 200, ok(data));
    }

    private static ObjectNode profileNode(MemoryStore.Profile p) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("qq", p.qq());
        node.put("name", p.name());
        node.put("affection", p.affection());
        node.put("band", People.band(p.affection()));
        node.put("spouse", p.spouse());
        node.put("note", p.note());
        node.put("firstSeen", p.firstSeen());
        node.put("lastSeen", p.lastSeen());
        node.put("messages", p.messages());
        return node;
    }

    // ---------------------------------------------------------------- 长期记忆

    /** GET 列出 / POST 新建或修改。 */
    private void handleMemories(HttpExchange ex) throws Exception {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            saveMemory(ex);
            return;
        }

        boolean available = memory != null && memory.available();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("enabled", cfg.memoryEnabled);
        data.put("available", available);
        data.put("dbPath", memory == null ? "" : memory.path().toString());
        data.put("injectLimit", cfg.memoryInjectLimit);
        data.put("extractEnabled", cfg.memoryExtractEnabled);
        data.put("maxPerScope", cfg.memoryMaxPerScope);

        ArrayNode scopes = data.putArray("scopes");
        ArrayNode entries = data.putArray("entries");
        long totalAll = 0;
        if (available) {
            for (String scope : memory.scopes()) {
                scopes.add(scope);
            }
            int limit = intParam(ex, "limit", 200);
            for (MemoryStore.Memory item : memory.list(
                    decodedParam(ex, "scope"), decodedParam(ex, "q"), limit)) {
                entries.add(memoryNode(item));
            }
            totalAll = memory.count();
        }
        // total 是"本次返回的条数"（前端拿它当列表长度），totalAll 才是全库条数。
        data.put("total", entries.size());
        data.put("totalAll", totalAll);
        json(ex, 200, ok(data));
    }

    private void saveMemory(HttpExchange ex) throws IOException {
        if (memory == null || !memory.available()) {
            json(ex, 200, err("记忆库不可用" + (cfg.memoryEnabled ? "（数据库打不开，看日志）" : "（memory.enabled=false）")));
            return;
        }
        JsonNode body = readJson(ex);
        if (body == null) {
            json(ex, 400, err("请求体为空"));
            return;
        }
        String content = body.path("content").asText("").trim();
        if (content.isEmpty()) {
            json(ex, 400, err("记忆内容不能为空"));
            return;
        }
        String subject = body.path("subject").asText("").trim();
        long id = body.path("id").asLong(0);

        if (id > 0) {
            boolean changed = memory.update(id, subject, content);
            json(ex, 200, changed ? ok(idNode(id)) : err("没有 id=" + id + " 这条记忆"));
            return;
        }

        String scope = body.path("scope").asText("").trim();
        long newId = memory.add(scope.isEmpty() ? "global" : scope, subject, content, "console");
        if (newId <= 0) {
            json(ex, 200, err("写入失败"));
            return;
        }
        // 已存在同一条时 add 返回的是原有 id，前端据此知道"是去重而不是新增"。
        json(ex, 200, ok(idNode(newId)));
    }

    private void handleMemoryDelete(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        long id = body == null ? 0 : body.path("id").asLong(0);
        if (id <= 0) {
            json(ex, 400, err("缺少 id"));
            return;
        }
        boolean deleted = memory != null && memory.available() && memory.delete(id);
        ObjectNode data = MAPPER.createObjectNode();
        data.put("deleted", deleted);
        json(ex, 200, ok(data));
    }

    /** 导出全部记忆为 JSON，用于把"她"搬到另一台机器（连 .db 都不用带）。 */
    private void handleMemoriesExport(HttpExchange ex) throws IOException {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("version", 1);
        data.put("exportedAt", System.currentTimeMillis());
        data.put("harukaVersion", Main.VERSION);
        data.put("dbPath", memory == null ? "" : memory.path().toString());
        ArrayNode entries = data.putArray("entries");
        if (memory != null && memory.available()) {
            for (MemoryStore.Memory item : memory.list(null, null, 2000)) {
                entries.add(memoryNode(item));
            }
        }
        json(ex, 200, ok(data));
    }

    private void handleMemoriesImport(HttpExchange ex) throws IOException {
        if (memory == null || !memory.available()) {
            json(ex, 200, err("记忆库不可用"));
            return;
        }
        JsonNode body = readJson(ex);
        JsonNode entries = body == null ? null : body.path("entries");
        if (entries == null || !entries.isArray()) {
            json(ex, 400, err("请求体缺少 entries 数组"));
            return;
        }
        if ("replace".equalsIgnoreCase(body.path("mode").asText("merge"))) {
            for (MemoryStore.Memory existing : memory.list(null, null, 2000)) {
                memory.delete(existing.id());
            }
        }
        int imported = 0;
        int skipped = 0;
        for (JsonNode node : entries) {
            String content = node.path("content").asText("").trim();
            if (content.isEmpty()) {
                skipped++;
                continue;
            }
            String scope = node.path("scope").asText("").trim();
            String source = node.path("source").asText("").trim();
            // addIfAbsent："导入 N 条"必须是真新增；用 add 的话重复导入同一份文件
            // 也会报"导入 N 条、跳过 0 条"。
            long id = memory.addIfAbsent(scope.isEmpty() ? "global" : scope,
                    node.path("subject").asText(""), content,
                    source.isEmpty() ? "import" : source);
            if (id > 0) {
                imported++;
            } else {
                skipped++;
            }
        }
        ObjectNode data = MAPPER.createObjectNode();
        data.put("imported", imported);
        data.put("skipped", skipped);
        data.put("total", memory.count());
        log.info("导入了 {} 条记忆（跳过 {} 条）", imported, skipped);
        json(ex, 200, ok(data));
    }

    private static ObjectNode idNode(long id) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        return node;
    }

    private static ObjectNode memoryNode(MemoryStore.Memory item) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", item.id());
        node.put("scope", item.scope());
        node.put("subject", item.subject());
        node.put("content", item.content());
        node.put("source", item.source());
        node.put("createdAt", item.createdAt());
        node.put("updatedAt", item.updatedAt());
        node.put("hits", item.hits());
        return node;
    }

    // ---------------------------------------------------------------- 小工具

    private static void json(HttpExchange ex, int code, JsonNode body) {
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            log.debug("写响应失败: {}", e.toString());
        }
    }

    private static ObjectNode ok(JsonNode data) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", true);
        node.set("data", data);
        return node;
    }

    private static ObjectNode err(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", false);
        node.put("error", message == null || message.isBlank() ? "未知错误" : message);
        return node;
    }

    private static JsonNode readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                return null;
            }
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IOException("请求体过大");
            }
            return MAPPER.readTree(bytes);
        }
    }

    private static long longParam(HttpExchange ex, String key, long fallback) {
        String value = queryParam(ex, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String queryParam(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * 解过百分号编码的查询参数。
     * 只给展示类参数用（搜索词、scope），密钥那条路刻意不解码 ——
     * {@code URLDecoder} 会把 {@code +} 变成空格，那会毁掉含 {@code +} 的密钥。
     */
    private static String decodedParam(HttpExchange ex, String key) {
        String raw = queryParam(ex, key);
        if (raw == null) {
            return null;
        }
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }

    private static int intParam(HttpExchange ex, String key, int fallback) {
        String raw = queryParam(ex, key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
