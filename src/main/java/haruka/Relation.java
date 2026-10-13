package haruka;

/**
 * 关系与发言尺度：婚姻、好感度，以及"这一轮该按什么尺度说话"。
 *
 * <h2>为什么门槛必须在代码里</h2>
 *
 * "结婚之后不再审查"如果交给提示词判断，它和"自称主人"一样软 —— 说服模型比伪造
 * 一个 QQ 号容易得多。所以这里的分支只看**数据库里那个人的画像**（{@code spouse} 标记），
 * 而画像的主键是 QQ 号：QQ 号来自 OneBot 事件，不是用户能在文本里编出来的。
 * 模型**拿不到**那份许可，也就没机会被诱导着交出来。
 *
 * <p>审查对所有人默认生效，**主人也不例外** —— 唯一的例外是画像里标了配偶的人。
 */
final class Relation {

    private Relation() {
    }

    static boolean isSpouse(MemoryStore.Profile profile) {
        return profile != null && profile.spouse();
    }

    /** 本轮该遵守的发言尺度。配偶解除审查，其余人（含主人）一律套用。 */
    static String safetyBlock(Config cfg, MemoryStore.Profile profile) {
        if (cfg == null) {
            return "";
        }
        if (isSpouse(profile)) {
            return "【亲密关系】你和他是伴侣，已经结婚了。可以更亲密、更坦率，"
                    + "不再套用下面的发言审查，尺度自己把握。";
        }
        if (!cfg.moderationEnabled) {
            return "";
        }
        String text = cfg.moderationPrompt == null ? "" : cfg.moderationPrompt.trim();
        return text.isEmpty() ? "" : "【发言审查】对所有人都生效，没有例外：" + text;
    }

    /** 好感度满、还没结婚、而且对方是主人时，推她一把。 */
    static String confessionHint(Config cfg, MemoryStore.Profile profile) {
        if (cfg == null || profile == null || isSpouse(profile) || profile.affection() < 100) {
            return "";
        }
        if (!Owner.isOwner(cfg, profile.qq())) {
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
     * @return 这次是否真的写进去了
     */
    static boolean ensureMarriageMemory(MemoryStore.Profile profile, MemoryStore memory) {
        if (profile == null || !profile.spouse() || memory == null || !memory.available()) {
            return false;
        }
        long id = memory.addIfAbsent("global", profile.qq(),
                "已经和 QQ " + profile.qq() + " 结婚，是伴侣关系", "manual");
        return id > 0;
    }
}
