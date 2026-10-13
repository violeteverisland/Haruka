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

        // 仅控制台模式下不检查 access_token：这种跑法本来就是为了先把配置改好。
        if (!webOnly && cfg.accessToken.isBlank()) {
            System.err.println("配置文件里 server.access_token 还没填: " + path);
            System.exit(2);
            return;
        }

        ConfigStore store = new ConfigStore(path, cfg);
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
