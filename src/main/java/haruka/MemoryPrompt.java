package haruka;

import java.util.List;

/**
 * 把长期记忆拼成一段系统级提示。
 *
 * <p>{@code ChatService}（真实对话）和控制台的"完整链路自测"共用这一处逻辑，
 * 避免两条路各拼一份、慢慢走样。
 */
final class MemoryPrompt {

    private MemoryPrompt() {
    }

    /** 拼好的提示文本 + 这次用到的记忆条目（后者用于控制台回显"用了哪几条"）。 */
    record Block(String text, List<MemoryStore.Memory> items) {

        static final Block EMPTY = new Block("", List.of());

        boolean isEmpty() {
            return text.isEmpty();
        }
    }

    /**
     * 取该会话（含全局）相关记忆并拼成提示。顺带累加命中次数，让常被用到的记忆排前面。
     */
    static Block build(Config cfg, MemoryStore store, String scope) {
        if (store == null || !store.available() || cfg.memoryInjectLimit <= 0) {
            return Block.EMPTY;
        }
        String key = scope == null || scope.isBlank() ? "global" : scope.trim();
        List<MemoryStore.Memory> items = store.forInjection(key, cfg.memoryInjectLimit);
        if (items.isEmpty()) {
            return Block.EMPTY;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【你记得的事】下面是你之前记住的、和当前对话有关的信息。");
        sb.append("自然地用就好，不要说“根据我的记忆”这类话，也不要逐条复述。\n");
        for (MemoryStore.Memory item : items) {
            sb.append("- ");
            if (!item.subject().isEmpty()) {
                sb.append(item.subject()).append("：");
            }
            sb.append(item.content()).append('\n');
        }
        store.touch(items.stream().map(MemoryStore.Memory::id).toList());
        return new Block(sb.toString(), items);
    }
}
