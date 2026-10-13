package haruka;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 从 config.properties 读取配置。
 *
 * <p>配置文件是唯一权威来源：改文件（重启，或等 {@link ConfigWatcher} 自动重载）和控制台里改，
 * 效果完全一样。控制台只是可选的便利层。
 *
 * <p>所有字段都是 volatile：控制台线程和配置重载线程会就地修改它们，而网络线程和聊天线程
 * 随时在读。唯一带结构的是 wakeWords，它一律被赋成不可变列表，读方不需要加锁。
 */
public final class Config {

    public volatile String host = "0.0.0.0";
    public volatile int port = 18080;
    public volatile String accessToken = "";

    /** openai = OpenAI 兼容的 /chat/completions；gemini = Gemini 原生 /v1beta/models/... */
    public volatile String llmProvider = "openai";
    public volatile String llmBaseUrl = "https://api.openai.com/v1";
    public volatile String llmApiKey = "";
    public volatile String llmModel = "gpt-4o-mini";
    public volatile String llmSystemPrompt = "";
    /**
     * 人设文件（md/txt）。设置且存在时，它的内容是人设主体，
     * {@code llm.system_prompt} 追加在后面当补充指令。改动会被热重载。
     */
    public volatile String llmSystemPromptFile = "";
    public volatile int llmHistoryLimit = 20;
    public volatile double llmTemperature = 0.7;
    public volatile int llmMaxTokens = 1024;
    public volatile int llmTimeoutSeconds = 60;

    public volatile boolean privateEnabled = true;
    /**
     * 群聊是否要求"被叫到"（被 @ 或命中唤醒词）。
     * 关掉等于群里每条消息都回 —— 只适合很小的群。
     */
    public volatile boolean groupRequireAt = true;
    /**
     * 群聊唤醒词：正文里出现这些词就叫醒她，**不需要 @**，也不用管 group_require_at。
     * 配置键是 {@code bot.wake_words}（老键 {@code bot.trigger_names} 继续认）。
     */
    public volatile List<String> wakeWords = List.of();
    /**
     * 主人的 QQ 号（逗号分隔，可以有多个）。空 = 不设置主人。
     *
     * <p>设置之后她才知道该听谁的：群聊里她原本只拿到一串裸文本，
     * 连"谁在说话"都不知道，也就无从谈起谁是她主人。
     */
    public volatile List<String> ownerIds = List.of();
    /** 怎么称呼主人，也是提示里标出主人消息用的前缀。 */
    public volatile String ownerTitle = "主人";
    public volatile int workers = 4;

    /** 群聊里没被 @ 也不叫名字时，是否还会主动接话（靠规则，不额外调模型）。 */
    public volatile boolean proactiveEnabled = false;
    /** 只对"看起来像提问"的群消息主动接话；关掉则任何群消息都可能（配合限流）。 */
    public volatile boolean proactiveOnQuestion = true;
    /** 同一个群两次主动开口之间至少隔多久。 */
    public volatile int proactiveCooldownSeconds = 60;
    /** 同一个群每小时最多主动开口几次。 */
    public volatile int proactiveMaxPerHour = 6;

    // ---- 头像 ----

    /** 让她能"看见"发送者的头像：消息里出现下面那些词时，抓头像附给模型。 */
    public volatile boolean avatarEnabled = true;
    /** 命中这些词就把发送者头像附上（逗号分隔）。 */
    public volatile String avatarLookKeywords = "头像,长相,样子,照片,自拍";
    /** qlogo 的尺寸档位，常见值 40 / 100 / 140 / 640。 */
    public volatile int avatarSize = 640;
    /** 同一张头像多久内不重复下载。 */
    public volatile int avatarCacheMinutes = 30;
    public volatile String avatarCacheDir = "data/avatars";
    public volatile int avatarTimeoutSeconds = 20;

    // ---- 控制接口（与机器人本体解耦：可以只跑其中一边）----

    /** 是否在这个进程里同时提供控制接口。false = 纯机器人，不带任何 HTTP 服务。 */
    public volatile boolean webEnabled = true;
    public volatile String webHost = "127.0.0.1";
    public volatile int webPort = 18081;
    /**
     * 控制台密钥。请求带 {@code Authorization: Bearer <密钥>}。
     * 留空表示不校验 —— 只允许在监听本机时这么用，绑到外部地址会被拒绝启动。
     */
    public volatile String webSecret = "";
    /** 静态页面目录。留空表示只提供 API，页面你自己托管。 */
    public volatile String webStaticDir = "webui";

    // ---- 长期记忆（SQLite）----
    // 设计目标：一个 .db 文件 + 一个 config.properties 拷到另一台机器，她就还是她。

    /** 关掉就完全不碰数据库，行为和加这个功能之前一样。 */
    public volatile boolean memoryEnabled = true;
    /** 数据库文件。刻意用相对路径，让整个目录可以整体搬走。 */
    public volatile String memoryDbPath = "data/haruka.db";
    /** 每次请求最多注入几条记忆。0 = 不注入。 */
    public volatile int memoryInjectLimit = 8;
    /** 每轮对话后自动抽取值得长期记住的事。 */
    public volatile boolean memoryExtractEnabled = true;
    /** 短于这个字数的用户消息不触发抽取，省掉"嗯""哈哈"这类无意义调用。 */
    public volatile int memoryExtractMinChars = 6;
    /** 每个会话最多留多少条，超出时淘汰最久没用到的。 */
    public volatile int memoryMaxPerScope = 300;
    /**
     * 重要性门槛：她自己给每条候选打分，低于这个分数的一律不写进记忆。
     * 调高 = 她更挑（默认 0.7；调到 0.9 就只记"非常重要"的）。
     */
    public volatile double memoryMinImportance = 0.7;
    /**
     * 对方明确要求"记住"的触发词。命中这些词时**必须落一条**，
     * 且绕开重要性门槛 —— 绝不能让"已经记好啦"变成假话。
     */
    public volatile String memoryRememberKeywords = "记住,记一下,别忘了,记得,帮我记,帮我记住";

    // ---- 画图 ----

    public volatile boolean imageEnabled = true;
    /** 生图模型。实测你的中转站上 gemini-3.1-flash-image 可用。 */
    public volatile String imageModel = "gemini-3.1-flash-image";
    /** 图片存哪。用相对路径，和记忆库一样跟着目录走。 */
    public volatile String imageSaveDir = "data/images";
    /** 追加到每张图提示词后面的风格描述，例如 "anime style, flat illustration"。 */
    public volatile String imageStyle = "";
    /** 怎么把图发给 OneBot：file（发本地路径，消息小）或 base64（兼容性更好）。 */
    public volatile String imageSendMode = "file";
    /** 生图很慢（实测约 17 秒），超时给宽一点。 */
    public volatile int imageTimeoutSeconds = 180;
    /** 一轮对话最多画几张，防止模型反复发标记。 */
    public volatile int imageMaxPerTurn = 2;

    public static Config load(String file) throws IOException {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) {
            throw new IOException("找不到配置文件: " + p.toAbsolutePath()
                    + System.lineSeparator()
                    + "请把 config.properties 放在运行目录，或运行: java -jar haruka.jar <配置文件路径>");
        }
        Properties props = new Properties();
        try (Reader r = new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8)) {
            props.load(r);
        }

        Config c = new Config();
        c.host = str(props, "server.host", c.host);
        c.port = num(props, "server.port", c.port);
        c.accessToken = str(props, "server.access_token", c.accessToken).trim();

        c.llmProvider = str(props, "llm.provider", c.llmProvider).trim().toLowerCase();
        c.llmBaseUrl = str(props, "llm.base_url", c.llmBaseUrl).trim();
        c.llmApiKey = str(props, "llm.api_key", c.llmApiKey).trim();
        c.llmModel = str(props, "llm.model", c.llmModel).trim();
        c.llmSystemPrompt = props.getProperty("llm.system_prompt", c.llmSystemPrompt);
        c.llmSystemPromptFile = str(props, "llm.system_prompt_file", c.llmSystemPromptFile);
        c.llmHistoryLimit = num(props, "llm.history_limit", c.llmHistoryLimit);
        c.llmTemperature = decimal(props, "llm.temperature", c.llmTemperature);
        c.llmMaxTokens = num(props, "llm.max_tokens", c.llmMaxTokens);
        c.llmTimeoutSeconds = num(props, "llm.timeout_seconds", c.llmTimeoutSeconds);

        c.privateEnabled = bool(props, "bot.private_enabled", c.privateEnabled);
        c.groupRequireAt = bool(props, "bot.group_require_at", c.groupRequireAt);
        // bot.wake_words 是正式键；bot.trigger_names 是改名前的键，继续认，老配置不用改。
        String wakeRaw = props.getProperty("bot.wake_words");
        if (wakeRaw == null) {
            wakeRaw = props.getProperty("bot.trigger_names", "");
        }
        List<String> wake = new ArrayList<>();
        for (String name : wakeRaw.split(",")) {
            String t = name.trim();
            if (!t.isEmpty()) {
                wake.add(t);
            }
        }
        c.wakeWords = List.copyOf(wake);
        // 主人名单：逗号分隔的 QQ 号（中英文逗号都认）。
        List<String> owners = new ArrayList<>();
        for (String id : str(props, "bot.owner_qq", "").split("[,，]")) {
            String t = id.trim();
            if (!t.isEmpty()) {
                owners.add(t);
            }
        }
        c.ownerIds = List.copyOf(owners);
        String ownerTitle = str(props, "bot.owner_title", c.ownerTitle).trim();
        // 空标题会让提示里出现「【】」这种残废标记，回落到默认。
        c.ownerTitle = ownerTitle.isEmpty() ? "主人" : ownerTitle;
        c.workers = Math.max(1, num(props, "bot.workers", c.workers));

        c.proactiveEnabled = bool(props, "bot.proactive_enabled", c.proactiveEnabled);
        c.proactiveOnQuestion = bool(props, "bot.proactive_on_question", c.proactiveOnQuestion);
        c.proactiveCooldownSeconds = Math.max(0, num(props, "bot.proactive_cooldown_seconds", c.proactiveCooldownSeconds));
        c.proactiveMaxPerHour = Math.max(1, num(props, "bot.proactive_max_per_hour", c.proactiveMaxPerHour));

        c.avatarEnabled = bool(props, "avatar.enabled", c.avatarEnabled);
        c.avatarLookKeywords = str(props, "avatar.look_keywords", c.avatarLookKeywords);
        c.avatarSize = num(props, "avatar.size", c.avatarSize);
        c.avatarCacheMinutes = Math.max(0, num(props, "avatar.cache_minutes", c.avatarCacheMinutes));
        c.avatarCacheDir = str(props, "avatar.cache_dir", c.avatarCacheDir);
        c.avatarTimeoutSeconds = num(props, "avatar.timeout_seconds", c.avatarTimeoutSeconds);

        c.webEnabled = bool(props, "web.enabled", c.webEnabled);
        c.webHost = str(props, "web.host", c.webHost);
        c.webPort = num(props, "web.port", c.webPort);
        // web.password 是改名前的键，继续认，方便老配置直接用。
        String secret = props.getProperty("web.secret");
        if (secret == null) {
            secret = props.getProperty("web.password", c.webSecret);
        }
        c.webSecret = secret == null ? "" : secret;
        c.webStaticDir = str(props, "web.static_dir", c.webStaticDir);
        if (c.webStaticDir == null) {
            c.webStaticDir = "";
        }

        c.memoryEnabled = bool(props, "memory.enabled", c.memoryEnabled);
        c.memoryDbPath = str(props, "memory.db_path", c.memoryDbPath);
        c.memoryInjectLimit = num(props, "memory.inject_limit", c.memoryInjectLimit);
        c.memoryExtractEnabled = bool(props, "memory.extract_enabled", c.memoryExtractEnabled);
        c.memoryExtractMinChars = num(props, "memory.extract_min_chars", c.memoryExtractMinChars);
        c.memoryMaxPerScope = Math.max(1, num(props, "memory.max_per_scope", c.memoryMaxPerScope));
        c.memoryMinImportance = decimal(props, "memory.min_importance", c.memoryMinImportance);
        c.memoryRememberKeywords = str(props, "memory.remember_keywords", c.memoryRememberKeywords);

        c.imageEnabled = bool(props, "image.enabled", c.imageEnabled);
        c.imageModel = str(props, "image.model", c.imageModel);
        c.imageSaveDir = str(props, "image.save_dir", c.imageSaveDir);
        c.imageStyle = props.getProperty("image.style", c.imageStyle);
        if (c.imageStyle == null) {
            c.imageStyle = "";
        }
        c.imageSendMode = str(props, "image.send_mode", c.imageSendMode).trim().toLowerCase();
        c.imageTimeoutSeconds = num(props, "image.timeout_seconds", c.imageTimeoutSeconds);
        c.imageMaxPerTurn = Math.max(1, num(props, "image.max_per_turn", c.imageMaxPerTurn));
        return c;
    }

    /** 把另一份配置的全部字段搬进来。重载后就地热更新，运行中的实例不用重建。 */
    public void copyFrom(Config other) {
        host = other.host;
        port = other.port;
        accessToken = other.accessToken;

        llmProvider = other.llmProvider;
        llmBaseUrl = other.llmBaseUrl;
        llmApiKey = other.llmApiKey;
        llmModel = other.llmModel;
        llmSystemPrompt = other.llmSystemPrompt;
        llmSystemPromptFile = other.llmSystemPromptFile;
        llmHistoryLimit = other.llmHistoryLimit;
        llmTemperature = other.llmTemperature;
        llmMaxTokens = other.llmMaxTokens;
        llmTimeoutSeconds = other.llmTimeoutSeconds;

        privateEnabled = other.privateEnabled;
        groupRequireAt = other.groupRequireAt;
        wakeWords = other.wakeWords;
        ownerIds = other.ownerIds;
        ownerTitle = other.ownerTitle;
        workers = other.workers;

        proactiveEnabled = other.proactiveEnabled;
        proactiveOnQuestion = other.proactiveOnQuestion;
        proactiveCooldownSeconds = other.proactiveCooldownSeconds;
        proactiveMaxPerHour = other.proactiveMaxPerHour;

        avatarEnabled = other.avatarEnabled;
        avatarLookKeywords = other.avatarLookKeywords;
        avatarSize = other.avatarSize;
        avatarCacheMinutes = other.avatarCacheMinutes;
        avatarCacheDir = other.avatarCacheDir;
        avatarTimeoutSeconds = other.avatarTimeoutSeconds;

        webEnabled = other.webEnabled;
        webHost = other.webHost;
        webPort = other.webPort;
        webSecret = other.webSecret;
        webStaticDir = other.webStaticDir;

        memoryEnabled = other.memoryEnabled;
        memoryDbPath = other.memoryDbPath;
        memoryInjectLimit = other.memoryInjectLimit;
        memoryExtractEnabled = other.memoryExtractEnabled;
        memoryExtractMinChars = other.memoryExtractMinChars;
        memoryMaxPerScope = other.memoryMaxPerScope;
        memoryMinImportance = other.memoryMinImportance;
        memoryRememberKeywords = other.memoryRememberKeywords;

        imageEnabled = other.imageEnabled;
        imageModel = other.imageModel;
        imageSaveDir = other.imageSaveDir;
        imageStyle = other.imageStyle;
        imageSendMode = other.imageSendMode;
        imageTimeoutSeconds = other.imageTimeoutSeconds;
        imageMaxPerTurn = other.imageMaxPerTurn;
    }

    private static String str(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null ? def : v.trim();
    }

    private static int num(Properties p, String key, int def) {
        try {
            String v = p.getProperty(key);
            return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double decimal(Properties p, String key, double def) {
        try {
            String v = p.getProperty(key);
            return v == null || v.isBlank() ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim());
    }
}
