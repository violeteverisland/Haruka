package haruka;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆的自动抽取：每轮对话结束后，另外问一次模型"这轮有没有值得长期记住的事"。
 *
 * <p>几点刻意的设计：
 * <ul>
 *   <li>抽取走单独的单线程池，异步执行 —— 绝不能让"记笔记"拖慢回复；</li>
 *   <li>失败只记日志，不影响任何主流程；</li>
 *   <li>用 {@link MemoryStore#fingerprint} 去重，所以同一件事反复说也只会有一条；</li>
 *   <li>人设会被拼在抽取指令前面，所以指令写得足够硬（要求只输出 JSON 数组），
 *       并且解析时容忍代码块围栏和前后废话。</li>
 * </ul>
 */
public final class MemoryExtractor {

    private static final Log log = Log.get(MemoryExtractor.class);
    /** 一轮最多让她记几条。刻意压得很低 —— 记忆是"关键记忆"，不是聊天记录。 */
    private static final int MAX_FACTS_PER_TURN = 2;
    /** 模型没给 importance 时的兜底值：给低分，宁可不记。 */
    private static final double MISSING_IMPORTANCE = 0.5;

    private static final String INSTRUCTION = """
            你是一个**严格的长期记忆守门人**。你的默认答案是「不记」——
            绝大多数日常对话都不该写进长期记忆。

            只有同时满足这两条才值得记：
            1. 过了半年仍然有用；
            2. 对方会期待你记得它。

            该记的例子：对方的名字或固定称呼、明确的长期偏好与忌讳、
            长期约定（「每周五我们打游戏」）、对方明确要求你记住的事、
            会实质影响你以后怎么回话的事实。

            有一条**优先于上面所有规则**：如果对方明确要求你记住
            （说了「记住」「别忘了」「记一下」这类话），那这件事**必须记** ——
            哪怕它看起来只是一次性的安排。对方明确要求记住的，永远不要漏。

            不该记的例子（出现这些一律不记）：寒暄、情绪、玩笑、当下正在做的事、
            与对方无关的一次性安排、临时状态、你从这句话里猜出来的东西、你自己说过的话。

            宁可漏记，也不要记一堆没用的。**一天聊一百句，通常一条都不该记。**

            输出要求（必须严格遵守）：
            - 只输出一个 JSON 数组，不要解释、不要代码块围栏、不要任何额外文字
            - 最多 %d 条；一条都不值得就输出 []
            - 每个元素形如 {"subject":"对方是谁（昵称或QQ号，不知道就留空）","content":"一句话的第三人称陈述","importance":0.0}
            - importance 取 0 到 1：0.5 = 有点用但无所谓，0.7 = 值得记，0.9 = 非常重要。
              拿不准就给低分 —— 系统只保留高于门槛的。

            对话：
            """.formatted(MAX_FACTS_PER_TURN);

    private final Config cfg;
    private final LlmClient llm;
    private final MemoryStore store;
    private final ExecutorService pool;

    public MemoryExtractor(Config cfg, LlmClient llm, MemoryStore store) {
        this.cfg = cfg;
        this.llm = llm;
        this.store = store;
        this.pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "haruka-memory");
            t.setDaemon(true);
            return t;
        });
    }

    /** 提交一轮对话做抽取。任何不满足条件的输入会被静默跳过。 */
    public void submit(String scope, String sender, String userText, String reply) {
        if (!eligible(userText)) {
            return;
        }
        String scopeKey = scope == null || scope.isBlank() ? "global" : scope.trim();
        String who = sender == null ? "" : sender.trim();
        String question = userText.trim();
        String answer = reply == null ? "" : reply.trim();

        pool.execute(() -> extract(scopeKey, who, question, answer));
    }

    /**
     * 同步跑一次抽取，返回新增条数。
     * 控制台的"完整链路自测"用它，这样不用真的收一条 QQ 消息也能验证抽取有没有在工作。
     */
    public int extractNow(String scope, String sender, String userText, String reply) {
        if (!eligible(userText)) {
            return 0;
        }
        String scopeKey = scope == null || scope.isBlank() ? "global" : scope.trim();
        return extract(scopeKey, sender == null ? "" : sender.trim(),
                userText.trim(), reply == null ? "" : reply.trim());
    }

    private boolean eligible(String userText) {
        if (store == null || !store.available() || !cfg.memoryExtractEnabled) {
            return false;
        }
        return userText != null && userText.trim().length() >= cfg.memoryExtractMinChars;
    }

    private int extract(String scope, String sender, String question, String reply) {
        try {
            // 对方明确说了"记住"吗？命中就一定要落一条 ——
            // 她经常顺口回"已经记好啦"，那句话必须是真的。
            boolean mustRemember = explicitRemember(question);
            String payload = (mustRemember ? "【对方这次明确要求你记住】\n" : "")
                    + "对方（" + (sender.isEmpty() ? "未知" : sender) + "）说：" + question
                    + "\n你的回复：" + reply;
            String raw = llm.chat(List.of(), payload, INSTRUCTION, List.of());
            List<Fact> facts = parse(raw);

            if (facts.isEmpty()) {
                if (mustRemember) {
                    // 模型没给出候选也不要紧：把这句话本身存下来，绝不让承诺落空。
                    long id = store.add(scope, sender, stripRememberLead(question), "auto");
                    if (id > 0) {
                        log.info("对方明确要求记住，兜底存下 1 条（会话 {}）", scope);
                        return 1;
                    }
                }
                log.debug("本轮没有抽到可长期记住的内容");
                return 0;
            }

            // 明确要求记住时不过门槛；其余情况她自己给的分数说了算。
            double gate = mustRemember ? 0 : cfg.memoryMinImportance;
            int added = 0;
            int rejected = 0;
            for (Fact fact : facts) {
                if (fact.importance() < gate) {
                    rejected++;
                    log.debug("候选「{}」重要性 {} 低于门槛 {}，不记", fact.content(), fact.importance(), gate);
                    continue;
                }
                String subject = fact.subject() == null || fact.subject().isBlank() ? sender : fact.subject().trim();
                long id = store.add(scope, subject, fact.content(), "auto");
                if (id > 0) {
                    added++;
                }
            }
            store.prune(scope, cfg.memoryMaxPerScope);
            if (added > 0) {
                log.info("记住了 {} 条（会话 {}）{}", added, scope, mustRemember ? "（对方明确要求记住）" : "");
            }
            if (rejected > 0) {
                log.debug("本轮 {} 条候选因为不够重要被丢掉", rejected);
            }
            return added;
        } catch (Exception e) {
            // 抽取失败不能影响聊天本身
            log.warn("记忆抽取失败: {}", Log.rootMessage(e));
            return 0;
        }
    }

    /** 对方是否明确要求"记住"。 */
    private boolean explicitRemember(String userText) {
        String keywords = cfg.memoryRememberKeywords == null ? "" : cfg.memoryRememberKeywords;
        for (String keyword : keywords.split("[,，]")) {
            String k = keyword.trim();
            if (!k.isEmpty() && userText.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 兜底存原话时，把开头的"记住，"这类指令词去掉 —— 存进去的应该是内容本身。 */
    static String stripRememberLead(String text) {
        String t = text == null ? "" : text.trim();
        for (String lead : new String[]{"帮我记住", "你要记住", "帮我记", "别忘了", "记一下", "记住", "记得"}) {
            if (t.startsWith(lead)) {
                String rest = t.substring(lead.length()).replaceAll("^[，,。：:\\s]+", "").trim();
                if (!rest.isEmpty()) {
                    return rest;
                }
            }
        }
        return t;
    }

    /** 解析模型返回的 JSON 数组，容忍代码块围栏和前后废话。 */
    static List<Fact> parse(String raw) {
        List<Fact> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            if (firstLine > 0) {
                text = text.substring(firstLine + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
            text = text.trim();
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return out;
        }
        try {
            JsonNode array = JsonHttp.mapper().readTree(text.substring(start, end + 1));
            if (!array.isArray()) {
                return out;
            }
            for (JsonNode node : array) {
                String content = node.path("content").asText("").trim();
                if (content.isEmpty()) {
                    continue;
                }
                double importance = node.has("importance")
                        ? node.path("importance").asDouble(MISSING_IMPORTANCE) : MISSING_IMPORTANCE;
                if (importance > 1) {
                    importance = importance / 100.0; // 有的模型给 0-100
                }
                out.add(new Fact(node.path("subject").asText("").trim(), content, importance));
                if (out.size() >= MAX_FACTS_PER_TURN) {
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("记忆抽取结果不是合法 JSON，丢弃: {}", JsonHttp.abbreviate(text));
        }
        return out;
    }

    /** 抽出来的一条候选事实。importance 是模型自评的"值得记的程度"。 */
    record Fact(String subject, String content, double importance) {
    }

    public void close() {
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
