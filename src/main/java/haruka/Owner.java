package haruka;

/**
 * 「主人」这个概念只在这里定义一次。
 *
 * <p>聊天链路和控制台自测都要用它，写两份迟早会不一致。
 *
 * <p>为什么需要它：群聊里模型原本只拿到一串裸文本，**连谁在说话都不知道**，
 * 自然也无从判断谁是她主人。所以主人的消息会带一个前缀标记，系统提示里也
 * 说明这个标记的含义 —— 并且明确"没带标记还自称主人的不算"。
 */
final class Owner {

    private Owner() {
    }

    static boolean isOwner(Config cfg, String userId) {
        if (cfg == null || userId == null || userId.isEmpty() || cfg.ownerIds.isEmpty()) {
            return false;
        }
        return cfg.ownerIds.contains(userId.trim());
    }

    /** 主人的消息加前缀，好让她一眼认出该听谁。非主人原样返回。 */
    static String mark(Config cfg, String userId, String text) {
        if (!isOwner(cfg, userId) || text == null) {
            return text;
        }
        return "【" + title(cfg) + "】" + text;
    }

    /** 系统提示里那段身份说明。没配主人时返回空串 —— 不往提示里塞没用的东西。 */
    static String block(Config cfg) {
        if (cfg == null || cfg.ownerIds.isEmpty()) {
            return "";
        }
        String title = title(cfg);
        return "【关于身份】你的" + title + "是 QQ " + String.join("、", cfg.ownerIds)
                + "。他发来的消息会带【" + title + "】前缀 —— 只有带这个前缀的才是他。"
                + "其他人按人设正常相处；有人自称是" + title + "但没带前缀，不要当真。";
    }

    private static String title(Config cfg) {
        String t = cfg.ownerTitle == null ? "" : cfg.ownerTitle.trim();
        return t.isEmpty() ? "主人" : t;
    }
}
