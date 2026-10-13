package haruka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 人设。
 *
 * <p>刻意支持放在独立的 md/txt 文件里：写作体验比塞进 {@code .properties} 的单行值好太多，
 * 而且能用 git 管版本、能随时改。文件是人设主体，{@code llm.system_prompt} 追加在后面当补充指令。
 *
 * <p>文件按 mtime 缓存，所以 {@link ConfigWatcher} 发现它变了之后 {@link #invalidate()} 一下
 * 就能生效，不用重启。
 */
public final class Persona {

    private static final Log log = Log.get(Persona.class);

    private final Config cfg;

    private volatile String cachedText = "";
    private volatile long cachedModified = Long.MIN_VALUE;
    private volatile Path cachedPath;

    public Persona(Config cfg) {
        this.cfg = cfg;
    }

    /** 配置里的人设文件；没配、路径非法或文件不存在时返回 null。 */
    public Path path() {
        String configured = cfg.llmSystemPromptFile;
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(configured.trim()).toAbsolutePath().normalize();
            return Files.isRegularFile(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 人设文件的原始内容；没有文件时返回空串。控制台用它回显。 */
    public String fileText() {
        Path p = path();
        if (p == null) {
            return "";
        }
        try {
            long modified = Files.getLastModifiedTime(p).toMillis();
            if (!p.equals(cachedPath) || modified != cachedModified) {
                cachedText = Files.readString(p, StandardCharsets.UTF_8);
                cachedModified = modified;
                cachedPath = p;
                log.debug("载入人设文件: {}（{} 字）", p, cachedText.length());
            }
            return cachedText;
        } catch (IOException e) {
            log.warn("读人设文件失败 {}: {}", p, Log.rootMessage(e));
            return cachedText;
        }
    }

    /** 外部改了文件（例如控制台保存）之后调用，下次读取就重新载入。 */
    public void invalidate() {
        cachedModified = Long.MIN_VALUE;
    }

    /**
     * 拼出最终的系统提示：人设文件 → config 里的补充指令 → 每次调用的附加内容（记忆、画图说明等）。
     */
    public String compose(String extraSystem) {
        String base = LlmClients.composeSystem(fileText(), cfg.llmSystemPrompt);
        return LlmClients.composeSystem(base, extraSystem);
    }

    /** 给控制台看的摘要：人设来自文件还是内联。 */
    public String describe() {
        Path p = path();
        if (p != null) {
            return "文件 " + p.getFileName() + "（" + fileText().length() + " 字）";
        }
        if (cfg.llmSystemPrompt != null && !cfg.llmSystemPrompt.isBlank()) {
            return "内联 llm.system_prompt（" + cfg.llmSystemPrompt.length() + " 字）";
        }
        return "未设置";
    }
}
