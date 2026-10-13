package haruka;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话与历史：每个会话（私聊一个、每个群一个）独立保存最近若干条消息。
 * 同一个会话的消息串行处理，避免同一人连发两条时回答顺序颠倒。
 *
 * <p>历史条数上限现读 {@code cfg.llmHistoryLimit}，所以调小它会立刻起作用。
 *
 * <p>三层能力都挂在这里：<b>人设</b>（{@link Persona}）、<b>长期记忆</b>（{@link MemoryPrompt}）、
 * <b>画图</b>（模型在回复里发 {@code [[draw: ...]]} 点单）。任何一层出错都不该影响聊天本身。
 */
public final class ChatService {

    private static final Log log = Log.get(ChatService.class);

    /** 模型用这个标记点单画图。中英文冒号都认，可选的 {@code | avatar} 指定参考图来源。 */
    private static final Pattern DRAW = Pattern.compile(
            "\\[\\[\\s*draw\\s*[:：]\\s*([^\\]|]+?)\\s*(?:\\|\\s*([a-zA-Z_]+)\\s*)?]]",
            Pattern.CASE_INSENSITIVE);

    /** 一次画图请求：画面描述 + 可选的参考图来源（avatar / group / bot）。 */
    record DrawRequest(String prompt, String source) {
    }

    /** 回复的去处。文本和图片分开回调，这样图片还在慢慢生成时文本能先发出去。 */
    public interface ReplySink {

        /** 文本回复；不会传入空白串。 */
        void text(String text);

        /** 稍后送达的图片。 */
        void image(Path file, String mimeType);

        /** 出错了。实现方不应抛出异常。 */
        void failed(Throwable error);
    }

    private final Config cfg;
    private final LlmClient llm;
    private final RuntimeStatus status;
    private final Persona persona;
    private final MemoryStore memory;
    private final MemoryExtractor extractor;
    private final ImageClient imageClient;
    private final AvatarFetcher avatars;
    /** 取合并转发正文的能力，由 OneBotServer 在构造完之后注入。 */
    private volatile Function<String, String> forwardResolver;

    /** 每个会话最近一次"先转发过来、还没被问到"的聊天记录。 */
    private final ConcurrentHashMap<String, CachedForward> recentForwards = new ConcurrentHashMap<>();
    /** 存下的转发记录多久之内还算数。 */
    private static final long FORWARD_TTL_MS = 5 * 60 * 1000L;

    private record CachedForward(String text, long at) {
    }

    private final ExecutorService pool;
    private final ExecutorService imagePool;
    private final ConcurrentHashMap<String, Deque<ChatMessage>> history = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public ChatService(Config cfg, LlmClient llm, RuntimeStatus status, Persona persona,
                       MemoryStore memory, MemoryExtractor extractor, ImageClient imageClient,
                       AvatarFetcher avatars) {
        this.cfg = cfg;
        this.llm = llm;
        this.status = status;
        this.persona = persona;
        this.memory = memory;
        this.extractor = extractor;
        this.imageClient = imageClient;
        this.avatars = avatars;
        this.pool = Executors.newFixedThreadPool(cfg.workers, r -> daemon(r, "haruka-chat"));
        // 画图很慢（实测十几秒），单独开一条线程，别把聊天 worker 占着。
        this.imagePool = Executors.newSingleThreadExecutor(r -> daemon(r, "haruka-image"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * 注入"取合并转发正文"的能力。
     *
     * <p>用 setter 而不是构造参数：ChatService 需要在 OneBotServer 之前构造（后者要拿着它），
     * 而取转发又只能用 OneBotServer 的连接，两者天然互相依赖，所以留一个补线的地方。
     */
    public void setForwardResolver(Function<String, String> resolver) {
        this.forwardResolver = resolver;
    }

    /**
     * @param sessionKey 形如 {@code private:123456} / {@code group:987654}，同时也是记忆的 scope
     * @param userId     发送者 QQ 号，用来抓头像
     * @param sender     发送者昵称或名片，用于给抽出来的记忆标注"关于谁"
     */
    public void submit(String sessionKey, String userId, String sender, String userText,
                       List<String> forwardIds, ReplySink sink) {
        pool.execute(() -> {
            Object lock = locks.computeIfAbsent(sessionKey, k -> new Object());
            synchronized (lock) {
                try {
                    Deque<ChatMessage> h = history.computeIfAbsent(sessionKey, k -> new ArrayDeque<>());

                    // 合并转发的正文得单独取。必须在这里（worker 线程）取：
                    // 在读线程里调 api.call 会等一个永远回不来的响应，直接死锁。
                    String text = withForwards(sessionKey, userText, forwardIds);

                    // 对方提到头像/长相时，先把头像抓下来附上 —— 她这才算真的"看得见"。
                    List<Path> attachments = attachmentsFor(userId, sender, text);
                    String reply = llm.chat(new ArrayList<>(h), text,
                            systemPrompt(sessionKey, !attachments.isEmpty()), attachments);

                    List<DrawRequest> draws = new ArrayList<>();
                    String visible = extractDraws(reply, cfg.imageMaxPerTurn, draws);

                    h.addLast(new ChatMessage("user", text));
                    h.addLast(new ChatMessage("assistant", visible.isBlank() ? "[发了一张图]" : visible));
                    while (h.size() > cfg.llmHistoryLimit) {
                        h.removeFirst();
                    }

                    boolean produced = false;
                    if (!visible.isBlank()) {
                        sink.text(visible);
                        produced = true;
                    }
                    if (!draws.isEmpty()) {
                        produced = true;
                        for (DrawRequest request : draws) {
                            imagePool.execute(() -> runDraw(request, sessionKey, userId, sink));
                        }
                    }
                    if (produced) {
                        status.messageReplied();
                    }

                    if (extractor != null) {
                        extractor.submit(sessionKey, sender, text, reply);
                    }
                } catch (Throwable t) {
                    status.failure(t.toString());
                    sink.failed(t);
                }
            }
        });
    }

    /**
     * 把合并转发的正文取回来接到消息后面。
     *
     * <p>两种来源：这次消息自带转发 id，或者这个会话刚才"先转发过来、还没被问到"的那一段
     * （QQ 里没法在同一条消息里既 @ 人又转发记录，所以那是常态）。
     *
     * <p>取不到时**明确告诉她取不到**，而不是留个占位符让她自己猜 ——
     * 之前正因为如此，她会把"看不到内容"当成内部推理直接吐出来。
     */
    private String withForwards(String sessionKey, String userText, List<String> forwardIds) {
        String resolved = resolveForwards(forwardIds);
        boolean fromCache = false;
        if (resolved.isBlank()) {
            CachedForward cached = recentForwards.remove(sessionKey);
            if (cached != null && System.currentTimeMillis() - cached.at() <= FORWARD_TTL_MS) {
                resolved = cached.text();
                fromCache = true;
            }
        }
        if (resolved.isBlank()) {
            if (forwardIds != null && !forwardIds.isEmpty()) {
                log.warn("[合并转发] 这次一段都没取到，已明确告知她看不到");
                return userText + "\n\n【对方转发了一段聊天记录，但这次没能取到内容 —— "
                        + "直接说明你看不到，不要猜】";
            }
            return userText;
        }
        log.info("[合并转发] {}一段聊天记录（{} 字）", fromCache ? "用上了之前存下的" : "取到了", resolved.length());
        return userText + "\n\n【对方转发了一段聊天记录，内容如下】\n" + resolved;
    }

    /** 把若干转发 id 取回来拼成文本；取不到的跳过。 */
    private String resolveForwards(List<String> forwardIds) {
        Function<String, String> resolver = forwardResolver;
        if (resolver == null || forwardIds == null || forwardIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : forwardIds) {
            try {
                String rendered = resolver.apply(id);
                if (rendered != null && !rendered.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(rendered);
                }
            } catch (Throwable t) {
                log.warn("取合并转发失败: {}", Log.rootMessage(t));
            }
        }
        return sb.toString();
    }

    /**
     * 先把一段转发的聊天记录取回来存着，不回复。
     * 用于"先转发记录、再 @ 她问"这种 QQ 里唯一可行的用法。
     */
    public void cacheForward(String sessionKey, List<String> forwardIds) {
        pool.execute(() -> {
            String text = resolveForwards(forwardIds);
            if (text.isBlank()) {
                log.warn("[合并转发] 没能取到内容，这次不缓存");
                return;
            }
            recentForwards.put(sessionKey, new CachedForward(text, System.currentTimeMillis()));
            log.info("[合并转发] 已为 {} 存下 1 段聊天记录（{} 字），等她下一句话",
                    sessionKey, text.length());
        });
    }

    /** 消息里出现「头像 / 长相 / 样子」这类词时，把发送者头像抓下来一起发给模型。 */
    private List<Path> attachmentsFor(String userId, String sender, String userText) {
        if (avatars == null || !cfg.avatarEnabled || userText == null || !mentionsAvatar(userText)) {
            return List.of();
        }
        Path avatar = avatars.member(userId);
        if (avatar == null) {
            log.warn("[看头像] 抓 {} 的头像失败，这次就当没看见", sender == null || sender.isEmpty() ? userId : sender);
            return List.of();
        }
        log.info("[看头像] 把 {} 的头像附给了模型", sender == null || sender.isEmpty() ? userId : sender);
        return List.of(avatar);
    }

    private boolean mentionsAvatar(String text) {
        String keywords = cfg.avatarLookKeywords == null ? "" : cfg.avatarLookKeywords;
        for (String keyword : keywords.split("[,，]")) {
            String k = keyword.trim();
            if (!k.isEmpty() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拼出这一轮的系统提示：看见的东西 → 人设 → 记忆 → 画图能力。
     * 顺序有意义：先说这次手上有什么，再是"我是谁"、"我知道什么"、"我能做什么"。
     */
    private String systemPrompt(String sessionKey, boolean withAvatar) {
        StringBuilder extra = new StringBuilder();
        // 代码自己追加的护栏，不写进用户的人设文件。
        // 见过一次模型把内部推理直接当回复发出来（还是英文的 "The user's request references…"），
        // 这一句拦掉那一类输出。
        extra.append("【输出纪律】回复里只写你要对对方说的话本身。"
                + "不要把思考过程、分析、推理或括号里的自我说明写进回复。");
        if (withAvatar) {
            extra.append('\n')
                    .append("【你看到的】这次附上了发送者**当前**的头像图片，就在这条消息里。")
                    .append("你可以直接看着它说话；不要说你看不到头像，也不要凭想象编造长相。");
        }
        String memoryBlock = MemoryPrompt.build(cfg, memory, sessionKey).text();
        if (!memoryBlock.isEmpty()) {
            if (extra.length() > 0) {
                extra.append('\n');
            }
            extra.append(memoryBlock);
        }
        if (imageClient != null && cfg.imageEnabled) {
            if (extra.length() > 0) {
                extra.append('\n');
            }
            extra.append(ImageClient.instruction(cfg.imageMaxPerTurn));
        }
        return persona == null ? LlmClients.composeSystem(cfg.llmSystemPrompt, extra.toString())
                : persona.compose(extra.toString());
    }

    /**
     * 把 {@code [[draw: ...]]} 标记从回复里摘出来。
     *
     * @param max 最多收集几条提示词；超出的标记照删不误，只是不再画
     * @return 去掉标记后的可见文本
     */
    static String extractDraws(String reply, int max, List<DrawRequest> out) {
        if (reply == null) {
            return "";
        }
        Matcher m = DRAW.matcher(reply);
        StringBuilder sb = new StringBuilder(reply.length());
        int last = 0;
        while (m.find()) {
            sb.append(reply, last, m.start());
            if (out.size() < max) {
                String prompt = m.group(1).trim();
                String source = m.group(2) == null ? "" : m.group(2).trim().toLowerCase();
                if (!prompt.isEmpty()) {
                    out.add(new DrawRequest(prompt, source));
                }
            }
            last = m.end();
        }
        sb.append(reply, last, reply.length());
        // 摘掉标记后常留下多余空行和悬空的空格，收拾一下再发出去。
        return sb.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").trim();
    }

    private void runDraw(DrawRequest request, String sessionKey, String userId, ReplySink sink) {
        if (imageClient == null) {
            return;
        }
        try {
            List<Path> references = referenceImages(request.source(), sessionKey, userId);
            long started = System.currentTimeMillis();
            ImageClient.Generated generated = imageClient.generate(request.prompt(), references);
            Path file = imageClient.save(generated);
            log.info("画好一张图: {}（{} KB，用了 {} 秒{}）", file.getFileName(),
                    generated.data().length / 1024, (System.currentTimeMillis() - started) / 1000,
                    references.isEmpty() ? "" : "，参考图 " + references.size() + " 张");
            sink.image(file, generated.mimeType());
        } catch (Throwable t) {
            status.failure("画图失败: " + t);
            log.warn("画图失败: {}", Log.rootMessage(t));
            sink.failed(t);
        }
    }

    /**
     * 把标记里的来源名换成真正的参考图。认不出来就当没点名 ——
     * 宁可画一张纯文字图，也不能把不相干的图当参考。
     */
    private List<Path> referenceImages(String source, String sessionKey, String userId) {
        if (avatars == null || source == null || source.isEmpty()) {
            return List.of();
        }
        Path picked = switch (source) {
            case "avatar", "sender", "me", "user" -> avatars.member(userId);
            case "bot", "self" -> avatars.member(status.selfId);
            case "group", "group_avatar" -> {
                String groupId = groupIdOf(sessionKey);
                yield groupId.isEmpty() ? null : avatars.group(groupId);
            }
            default -> null;
        };
        if (picked == null) {
            log.warn("画图点名了参考图「{}」但没取到，按纯文字生图处理", source);
            return List.of();
        }
        return List.of(picked);
    }

    private static String groupIdOf(String sessionKey) {
        return sessionKey != null && sessionKey.startsWith("group:")
                ? sessionKey.substring("group:".length()) : "";
    }

    /** 当前有多少个会话在内存里，控制台拿它做展示。 */
    public int sessionCount() {
        return history.size();
    }

    public void close() {
        pool.shutdownNow();
        imagePool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
            imagePool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
