package haruka;

/**
 * 关系与发言尺度：好感度、婚姻、以及"这一轮该按什么尺度说话"。
 *
 * <h2>为什么门槛必须在代码里</h2>
 *
 * "结婚之后不再审查"如果交给提示词判断，它和"自称主人"一样软 —— 说服模型比伪造
 * 一个 QQ 号容易得多。所以这里的分支只看 {@code cfg.spouseQq} 和发送者的真实 QQ 号
 * （QQ 号来自 OneBot 事件，不是用户能编的文本）。模型**拿不到**那份许可，
 * 也就没机会被诱导着交出来。
 *
 * <p>审查对所有人默认生效，**主人也不例外** —— 唯一的例外是登记过的配偶。
 */
final class Relation {

    private Relation() {
    }

    static boolean isSpouse(Config cfg, String userId) {
        if (cfg == null || userId == null || userId.isBlank()) {
            return false;
        }
        String spouse = cfg.spouseQq == null ? "" : cfg.spouseQq.trim();
        return !spouse.isEmpty() && spouse.equals(userId.trim());
    }

    /** 本轮该遵守的发言尺度。配偶解除审查，其余人（含主人）一律套用。 */
    static String safetyBlock(Config cfg, String userId) {
        if (cfg == null) {
            return "";
        }
        if (isSpouse(cfg, userId)) {
            return "【亲密关系】你和他是伴侣，已经结婚了。可以更亲密、更坦率，"
                    + "不再套用下面的发言审查，尺度自己把握。";
        }
        if (!cfg.moderationEnabled) {
            return "";
        }
        String text = cfg.moderationPrompt == null ? "" : cfg.moderationPrompt.trim();
        return text.isEmpty() ? "" : "【发言审查】对所有人都生效，没有例外：" + text;
    }

    /** 好感度满、还没结婚时，推她一把。只对主人说。 */
    static String confessionHint(Config cfg, String userId) {
        if (cfg == null || cfg.affection < 100 || isSpouse(cfg, userId)) {
            return "";
        }
        if (!Owner.isOwner(cfg, userId)) {
            return "";
        }
        return "【心意】你对他的好感已经满了 —— 你想向他表白。";
    }

    /**
     * 结婚写进长期记忆。
     *
     * <p>内容里**不放日期** —— 记忆是按内容指纹去重的，带上日期就每天都是一条新记忆。
     * 靠指纹去重，这里反复调用也只会在第一次真正写入。
     *
     * @return 这次是否真的写进去了（用于决定要不要打日志）
     */
    static boolean ensureMarriageMemory(Config cfg, MemoryStore memory) {
        if (cfg == null || memory == null || !memory.available()) {
            return false;
        }
        String spouse = cfg.spouseQq == null ? "" : cfg.spouseQq.trim();
        if (spouse.isEmpty()) {
            return false;
        }
        long id = memory.addIfAbsent("global", spouse, "已经和 QQ " + spouse + " 结婚，是伴侣关系", "manual");
        return id > 0;
    }
}
