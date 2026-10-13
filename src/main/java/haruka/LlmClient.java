package haruka;

import java.nio.file.Path;
import java.util.List;

/**
 * 大模型客户端。换协议只需要换实现，ChatService 不关心下面走的是哪家。
 */
public interface LlmClient {

    /**
     * 发一次对话，返回纯文本回复。
     *
     * @param history      当前会话已有的往返消息
     * @param userText     本轮用户发来的内容
     * @param systemPrompt 完整的系统提示（人设 + 记忆 + 能力说明），由调用方拼好。可为空。
     *                     实现方不再自己去读 config 里的人设 —— 人设可能来自一个独立文件。
     * @param attachments  随消息一起发给模型的图片（头像等）。可为空。
     */
    String chat(List<ChatMessage> history, String userText, String systemPrompt,
                List<Path> attachments) throws Exception;

    /** 拉取当前 base_url 上可用的模型名，供控制台填 model。 */
    List<String> listModels() throws Exception;
}
