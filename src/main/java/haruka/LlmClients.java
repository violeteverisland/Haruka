package haruka;

/** 按 llm.provider 选一个实现。 */
public final class LlmClients {

    public static final String OPENAI = "openai";
    public static final String GEMINI = "gemini";

    private LlmClients() {
    }

    /**
     * 把 provider 的各种别名收敛成 {@link #OPENAI} 或 {@link #GEMINI}。
     * 空值按 openai 处理，这样配置里不写 provider 也能跑。
     */
    public static String normalizeProvider(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        return switch (p) {
            case "gemini", "google", "google_gemini" -> GEMINI;
            case "", "openai", "openai_compatible", "openai-compatible" -> OPENAI;
            default -> throw new IllegalArgumentException(
                    "不认识的 llm.provider: " + provider + "（可选 openai / gemini）");
        };
    }

    /** 面向用户的协议描述，控制台和日志都用它。 */
    public static String describe(String provider) {
        return GEMINI.equals(normalizeProvider(provider)) ? "Gemini 原生" : "OpenAI 兼容";
    }

    /**
     * 把基础人设和附加系统内容拼成最终的系统提示。
     * 附加内容放在后面，因为它（例如记忆注入）应该比人设更有"当下"的权重。
     */
    public static String composeSystem(String base, String extra) {
        String b = base == null ? "" : base.trim();
        String e = extra == null ? "" : extra.trim();
        if (e.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return e;
        }
        return b + System.lineSeparator() + System.lineSeparator() + e;
    }

    public static LlmClient create(Config cfg) {
        return GEMINI.equals(normalizeProvider(cfg.llmProvider))
                ? new GeminiLlmClient(cfg)
                : new OpenAiLlmClient(cfg);
    }
}
