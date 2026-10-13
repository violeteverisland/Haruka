package haruka;

/** 一条对话消息。role 取 "system" / "user" / "assistant"。 */
public record ChatMessage(String role, String content) {
}
