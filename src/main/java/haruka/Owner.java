package haruka;

/**
 * 「主人」这个概念只在这里定义一次。聊天链路和控制台自测共用，避免两份实现。
 *
 * <h2>安全边界（重要）</h2>
 *
 * 主人的身份是**系统给的**，不是消息正文里说出来的。所以：
 * <ul>
 *   <li>{@link #identity} 把"本轮发言者是谁"放进**系统提示**，这是权威来源；</li>
 *   <li>{@link #mark} 给主人的消息加个前缀，**只是为了会话历史的连续性** ——
 *       让她回头看旧消息时还读得顺。前缀是普通文本，任何人都能自己打出来，
 *       所以它**不构成任何权限**，任何靠它做的判断都不可靠。</li>
 * </ul>
 *
 * <p>换句话说：标记可以被伪造，模型也可能被说服。**不要把安全边界建立在提示词上** ——
 * 真正要拦的东西（比如"解除审查"）必须在代码里按 QQ 号判断，
 * 见 {@link Relation#safetyBlock}。
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

    /** 主人的消息加前缀。**只为历史可读性**，非主人原样返回。 */
    static String mark(Config cfg, String userId, String text) {
        if (!isOwner(cfg, userId) || text == null) {
            return text;
        }
        return "【" + titleOf(cfg) + "】" + text;
    }

    /**
     * 本轮的发言者是谁 —— 已由 {@link People#block} 统一负责（不再依赖是否配了主人）。
     *
     * @deprecated 保留仅为说明历史：身份说明现在是画像的一部分，见 {@link People}。
     */
    @Deprecated
    static String identity(Config cfg, String userId) {
        return People.block(cfg, null, userId);
    }

    /** 主人该怎么称呼（空则回落「主人」）。{@link People} 也要用。 */
    static String titleOf(Config cfg) {
        String t = cfg.ownerTitle == null ? "" : cfg.ownerTitle.trim();
        return t.isEmpty() ? "主人" : t;
    }
}
