package haruka;

/**
 * 启动入口。
 *
 * <p>三种跑法：
 * <ul>
 *   <li>{@code java -jar haruka.jar config.properties} —— 机器人 + 控制接口；</li>
 *   <li>{@code web.enabled=false} —— 纯机器人，一个 HTTP 端口都不开；</li>
 *   <li>{@code java -jar haruka.jar config.properties --web-only} —— 只跑控制接口，
 *       不连 SnowLuma、不占 QQ 端口，用来改配置或排查。</li>
 * </ul>
 *
 * <p>配置以 config.properties 为准，{@link ConfigWatcher} 会在文件被手工改动后自动热重载。
 */
public final class Main {

    public static final String VERSION = "0.1.0";

    private static final Log log = Log.get(Main.class);

    public static void main(String[] args) {
        // Java-WebSocket 用 slf4j 打自己的内部日志，默认级别会刷屏；
        // 在它被加载之前压到 warn，只有真出问题才说话。用户显式设置了就不覆盖。
        putIfAbsent("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        putIfAbsent("org.slf4j.simpleLogger.showThreadName", "false");

        if (hasFlag(args, "--debug")) {
            Log.setDebugEnabled(true);
        }
        boolean webOnly = hasFlag(args, "--web-only");

        String path = configPath(args);
        Config cfg;
        try {
            cfg = Config.load(path);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        ConfigStore store = new ConfigStore(path, cfg);

        // server.access_token 没填（或还是模板里那个 CHANGE_ME）就当场生成一个，写回配置文件并大声打印。
        // 绝不能留一个公开的默认值 —— server.host 默认是 0.0.0.0，那样谁都能伪装成 SnowLuma 连上 18080。
        // 「只跑控制接口」模式不生成：那种跑法本来就是为了先把配置改好。
        if (!webOnly && accessTokenUnset(cfg.accessToken)) {
            String generated = randomToken();
            boolean saved = false;
            try {
                store.apply(java.util.Map.of("server.access_token", generated));
                saved = true;
            } catch (java.io.IOException e) {
                // 写不进去也不要紧：内存里这份照样能用，用户在日志里看得到。
                cfg.accessToken = generated;
                log.warn("生成的 access_token 写不回配置文件: {}", Log.rootMessage(e));
            }
            announceToken(generated, saved, path);
        }
        RuntimeStatus status = new RuntimeStatus();
        status.webOnly = webOnly;

        // 人设：可以来自独立文件，改文件即生效（ConfigWatcher 一起盯着）。
        Persona persona = new Persona(cfg);

        LlmClient llm;
        try {
            llm = new LlmRouter(cfg);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        // 画图客户端：只在开启时才建。
        ImageClient imageClient = cfg.imageEnabled ? new ImageClient(cfg) : null;
        // 头像抓取：同样只在开启时才建。
        AvatarFetcher avatars = cfg.avatarEnabled ? new AvatarFetcher(cfg) : null;

        // 配置文件是唯一权威来源：手改文件后自动热重载，控制台只是可选的便利层。
        ConfigWatcher watcher = new ConfigWatcher(store, status, persona);
        watcher.start();

        if (!webOnly && cfg.llmApiKey.isBlank()) {
            log.warn("llm.api_key 为空，如果你的服务端需要鉴权会返回 401");
        }

        // 长期记忆。打不开数据库不会让机器人挂掉，只是记忆功能本次不可用。
        MemoryStore memory = null;
        MemoryExtractor extractor = null;
        if (cfg.memoryEnabled) {
            MemoryStore opened = new MemoryStore(cfg.memoryDbPath);
            opened.open();
            if (opened.available()) {
                memory = opened;
                extractor = new MemoryExtractor(cfg, llm, opened);
            }
        }

        ChatService chat = null;
        OneBotServer server = null;
        if (!webOnly) {
            chat = new ChatService(cfg, llm, status, persona, memory, extractor, imageClient, avatars);
            server = new OneBotServer(cfg, chat, status);
            // 合并转发的正文只能用 OneBot 那条连接去取，而那条连接在 server 手里，
            // 所以两者都构造好之后再把线接上。
            chat.setForwardResolver(server::resolveForward);
            server.start();
        }

        WebConsole console = null;
        if (webOnly || cfg.webEnabled) {
            console = new WebConsole(cfg, store, status, llm, memory, extractor, persona, webOnly);
            try {
                console.start();
            } catch (Exception e) {
                console = null;
                log.error("控制接口启动失败: {}", e.getMessage());
                if (webOnly) {
                    System.exit(2);
                    return;
                }
            }
        }

        final ChatService runningChat = chat;
        final OneBotServer runningServer = server;
        final WebConsole runningConsole = console;
        final MemoryStore runningMemory = memory;
        final MemoryExtractor runningExtractor = extractor;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在退出……");
            watcher.stop();
            if (runningServer != null) {
                try {
                    runningServer.stop(1000);
                } catch (Exception ignored) {
                    // 退出阶段不再处理
                }
            }
            if (runningChat != null) {
                runningChat.close();
            }
            if (runningExtractor != null) {
                runningExtractor.close();
            }
            if (runningConsole != null) {
                runningConsole.stop();
            }
            if (runningMemory != null) {
                // 收尾落盘，保证 .db 单独拷走就是完整的记忆。
                runningMemory.close();
            }
        }));

        log.info("haruka {} 启动{}", VERSION, webOnly ? "（--web-only：只跑控制接口，机器人本体没有启动）" : "");
        if (!webOnly) {
            log.info("模型协议: {} ({})  模型: {}  地址: {}",
                    LlmClients.describe(cfg.llmProvider), LlmClients.normalizeProvider(cfg.llmProvider),
                    cfg.llmModel, cfg.llmBaseUrl);
            log.info("私聊={} 群聊需@={} 会话历史上限={} 条",
                    cfg.privateEnabled, cfg.groupRequireAt, cfg.llmHistoryLimit);
            log.info("主动接话={}{}   头像={}",
                    cfg.proactiveEnabled,
                    cfg.proactiveEnabled
                            ? "（只看像提问的，冷却 " + cfg.proactiveCooldownSeconds + " 秒 / 每小时最多 "
                                    + cfg.proactiveMaxPerHour + " 次）" : "（未开启，只响应 @ 和名字）",
                    cfg.avatarEnabled);
            log.info("等待 SnowLuma 反向 WS 连接: ws://<本机IP>:{}/onebot/v11/ws", cfg.port);
        }
        log.info("配置文件: {}（改动会自动重载）", store.path());
        if (memory != null) {
            log.info("长期记忆: {}（自动抽取={}，每次注入上限={} 条）",
                    memory.path(), cfg.memoryExtractEnabled, cfg.memoryInjectLimit);
            // 一次性迁移：老配置里的 bot.affection / bot.spouse_qq 搬进画像（幂等，有画像就不动）。
            seedLegacyRelation(cfg, memory, store);
            // 启动时把画像里所有配偶的"结婚"补进记忆（幂等：写过的不会再写）。
            // 这样即使她还没收到任何消息，这条也已经在记忆里了。
            int seeded = 0;
            for (MemoryStore.Profile p : memory.listProfiles(0)) {
                if (Relation.ensureMarriageMemory(p, memory)) {
                    seeded++;
                }
            }
            if (seeded > 0) {
                log.info("[关系] 补写了 {} 条结婚记忆", seeded);
            }
        } else if (cfg.memoryEnabled) {
            log.warn("长期记忆不可用（数据库打不开），机器人其余功能不受影响");
        } else {
            log.info("长期记忆已关闭（memory.enabled=false）");
        }

        if (console != null) {
            log.info("控制接口: http://{}:{}", displayHost(cfg.webHost), cfg.webPort);
            if (cfg.webSecret == null || cfg.webSecret.isBlank()) {
                log.warn("web.secret 为空：本机任何人都能读取和改写你的配置");
            }
        } else if (!webOnly && !cfg.webEnabled) {
            log.info("控制接口已关闭（web.enabled=false）—— 这是纯机器人进程");
        }

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String displayHost(String host) {
        return host == null || host.isBlank() || "0.0.0.0".equals(host) ? "127.0.0.1" : host;
    }

    /**
     * {@code server.access_token} 是否还没被真正设置过：空，或者还是模板里的占位符。
     *
     * <p>占位符必须当成"没设"。否则服务会带着一个**公开的默认口令**跑起来，
     * 而 {@code server.host} 默认是 {@code 0.0.0.0} —— 等于把消息通道敞开。
     */
    private static boolean accessTokenUnset(String token) {
        return token == null || token.isBlank() || token.trim().equalsIgnoreCase("CHANGE_ME");
    }

    /** 生成连接凭据。用 URL 安全的 base64，避免复制粘贴时被特殊字符坑到。 */
    private static String randomToken() {
        byte[] buf = new byte[24];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * 把凭据大声打出来。
     *
     * <p>用户要在 SnowLuma 里手抄这一串，日志是唯一可靠的下发渠道 ——
     * 所以不能只在配置文件里悄悄写一行，那样等于让人去猜。
     */
    private static void announceToken(String token, boolean saved, String path) {
        log.info("");
        log.info("================== 首次启动：已生成连接凭据 ==================");
        log.info("  server.access_token = {}", token);
        log.info("  把它原样填进 SnowLuma「反向 WebSocket」的 Access Token。");
        if (saved) {
            log.info("  已写回配置文件，以后不用再管：{}", path);
        } else {
            log.info("  写不进配置文件，只在本进程内有效（重启会重新生成）。");
        }
        log.info("==============================================================");
        log.info("");
    }

    private static void putIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一次性迁移：把老配置里的 {@code bot.affection} / {@code bot.spouse_qq} 搬进画像。
     *
     * <p>这两个键已经废弃（配置模板里删掉、控制台不再显示），但已经填过的人不该丢数据 ——
     * 尤其 {@code spouse_qq}：它代表"不再套用发言审查"，丢了会静默变回被审查。
     *
     * <p>只在目标还没有画像时写一次，绝不覆盖已有的画像。
     */
    private static void seedLegacyRelation(Config cfg, MemoryStore memory, ConfigStore store) {
        if (memory == null || !memory.available() || store == null) {
            return;
        }
        java.util.Properties legacy = new java.util.Properties();
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(
                store.path(), java.nio.charset.StandardCharsets.UTF_8)) {
            legacy.load(reader);
        } catch (Exception e) {
            return;   // 读不到就当没填过
        }
        String spouse = legacy.getProperty("bot.spouse_qq", "").trim();
        String affectionRaw = legacy.getProperty("bot.affection", "").trim();
        if (spouse.isEmpty() && affectionRaw.isEmpty()) {
            return;
        }
        String owner = cfg.ownerIds.isEmpty() ? "" : cfg.ownerIds.get(0);
        String target = spouse.isEmpty() ? owner : spouse;
        if (target.isEmpty()) {
            log.warn("[关系] 配置里有 bot.affection / bot.spouse_qq，但没有主人可归属，跳过迁移");
            return;
        }
        if (memory.profile(target) != null) {
            return;   // 画像已经有了，不覆盖
        }
        int affection = 0;
        try {
            affection = Math.max(0, Math.min(100, Integer.parseInt(affectionRaw)));
        } catch (Exception ignored) {
            // 不是数字就当 0
        }
        memory.saveProfile(target, "", affection, !spouse.isEmpty(), "");
        log.info("[关系] 已把老配置搬进画像：QQ {} 好感度 {} 配偶 {} —— 这两个键可以从配置里删掉了",
                target, affection, !spouse.isEmpty());
    }

    /** 第一个不以 -- 开头的参数当配置文件路径。 */
    private static String configPath(String[] args) {
        for (String arg : args) {
            if (arg != null && !arg.startsWith("--")) {
                return arg;
            }
        }
        return "config.properties";
    }
}
