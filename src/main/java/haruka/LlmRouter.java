package haruka;

import java.nio.file.Path;
import java.util.List;

/**
 * 大模型客户端路由：按 {@code cfg.llmProvider} 选实现，并允许在控制台改完配置后热切换。
 *
 * <p>只有"协议类型"变了才需要换实现对象；base_url、model、api_key 这些是每次调用现读
 * {@code Config} 的，所以改完立刻生效。判断依据是规范化后的 provider 名。
 */
public final class LlmRouter implements LlmClient {

    private final Config cfg;
    private volatile LlmClient delegate;
    private volatile String builtFor = "";

    public LlmRouter(Config cfg) {
        this.cfg = cfg;
        rebuildIfNeeded();
    }

    @Override
    public String chat(List<ChatMessage> history, String userText, String systemPrompt,
                       List<Path> attachments) throws Exception {
        rebuildIfNeeded();
        return delegate.chat(history, userText, systemPrompt, attachments);
    }

    @Override
    public List<String> listModels() throws Exception {
        rebuildIfNeeded();
        return delegate.listModels();
    }

    private void rebuildIfNeeded() {
        String wanted = LlmClients.normalizeProvider(cfg.llmProvider);
        LlmClient current = delegate;
        if (current == null || !wanted.equals(builtFor)) {
            delegate = LlmClients.create(cfg);
            builtFor = wanted;
        }
    }
}
