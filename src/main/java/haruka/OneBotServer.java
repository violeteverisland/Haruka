package haruka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反向 WebSocket 服务端：SnowLuma 主动连进来，事件从这条连接上来，回复也从这条连接发出。
 * 只做三件事：鉴权、解析消息事件、把回复发回去。群管理、扩展、审核一概没有。
 *
 * <p>触发判定和访问令牌都是现读 {@code Config} 的，所以在控制台改完立刻生效。
 */
public final class OneBotServer extends WebSocketServer {

    private static final Log log = Log.get(OneBotServer.class);
    private static final Pattern CQ = Pattern.compile("\\[CQ:([a-zA-Z_]+)((?:,[^\\]]*)?)]");
    /** 整条正文只剩 @ 占位符（例如对方只点了 @ 没说话）。 */
    private static final Pattern ONLY_AT = Pattern.compile("^(\\[@\\]\\s*)+$");
    /** 看起来像提问的收尾（便宜启发式，不额外调模型）。 */
    private static final Pattern QUESTION = Pattern.compile(
            ".*(吗|呢|怎么|咋|为什么|为啥|什么|啥|哪里|哪儿|哪个|谁|有没有|能不能|可不可以|是不是|好不好)[\\s~～。！!？?]*$");
    /** 合并转发在 raw_message 里长这样：{@code [CQ:forward,id=xxx]}。 */
    private static final Pattern FORWARD_CQ = Pattern.compile("\\[CQ:forward,[^\\]]*?id=([^,\\]]+)");
    /** 合并转发渲染出来的正文上限，免得一段超长聊天记录把上下文撑爆。 */
    private static final int MAX_FORWARD_CHARS = 3000;
    private static final int MAX_FORWARD_NODES = 50;
    private static final int MAX_TEXT_PER_MESSAGE = 600;

    private final Config cfg;
    private final ChatService chat;
    private final RuntimeStatus status;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OneBotApi api = new OneBotApi(mapper);
    private volatile String selfId = "";
    /** 每个群最近的主动接话时间，用来做冷却和每小时上限。 */
    private final ConcurrentHashMap<String, Deque<Long>> proactiveLog = new ConcurrentHashMap<>();

    public OneBotServer(Config cfg, ChatService chat, RuntimeStatus status) {
        super(bindAddress(cfg));
        this.cfg = cfg;
        this.chat = chat;
        this.status = status;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    private static InetSocketAddress bindAddress(Config cfg) {
        if (cfg.host == null || cfg.host.isBlank() || "0.0.0.0".equals(cfg.host)) {
            return new InetSocketAddress(cfg.port);
        }
        return new InetSocketAddress(cfg.host, cfg.port);
    }

    @Override
    public void onStart() {
        log.info("反向 WebSocket 监听中: {}:{}  (路径 /onebot/v11/ws)", cfg.host, cfg.port);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String problem = authorize(handshake);
        if (problem != null) {
            log.warn("拒绝握手: {} (来自 {})", problem, conn.getRemoteSocketAddress());
            conn.close(1008, "unauthorized");
            return;
        }

        String sid = handshake.getFieldValue("X-Self-ID");
        if (sid != null && !sid.isBlank()) {
            selfId = sid.trim();
        }
        api.bind(conn);
        status.connected(selfId, String.valueOf(conn.getRemoteSocketAddress()));
        log.info("接入端已连接: path={} self_id={} 来自 {}",
                handshake.getResourceDescriptor(), selfId.isEmpty() ? "?" : selfId, conn.getRemoteSocketAddress());

        // 注意：Java-WebSocket 的 onOpen 和 onMessage 跑在同一个 worker 线程上。
        // 在这里同步等待 API 响应会把读线程一起堵死，事件再也读不进来，所以必须异步探活。
        Thread probe = new Thread(() -> {
            try {
                JsonNode info = api.call("get_login_info", mapper.createObjectNode(), 8000);
                JsonNode data = info.path("data");
                status.loginInfo(data.path("nickname").asText(""));
                log.info("登录信息: {}", data);
            } catch (Exception e) {
                log.warn("get_login_info 调用失败（不影响收发消息）: {}", e.toString());
            }
        }, "haruka-login-probe");
        probe.setDaemon(true);
        probe.start();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        api.unbind(conn);
        status.disconnected(null);
        log.warn("接入端断开: code={} reason={} remote={}", code, reason, remote);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // conn == null 是服务端级错误，最常见的就是端口被占用或 host 不可绑定。
        if (conn == null) {
            log.error("WebSocket 服务端错误（检查端口 {} 是否已被占用、host {} 是否可绑定）: {}",
                    cfg.port, cfg.host, ex.toString());
        } else {
            log.warn("WebSocket 错误: {}", ex.toString());
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonNode root;
        try {
            root = mapper.readTree(message);
        } catch (Exception e) {
            log.debug("忽略无法解析的报文");
            return;
        }
        if (api.onResponse(root)) {
            return;
        }
        String postType = root.path("post_type").asText("");
        if (!"message".equals(postType)) {
            return; // meta_event / notice / request 一律不管
        }
        handleMessage(root);
    }

    private void handleMessage(JsonNode root) {
        String type = root.path("message_type").asText("");
        String userId = text(root.get("user_id"));
        String groupId = text(root.get("group_id"));
        String sid = selfId.isEmpty() ? text(root.get("self_id")) : selfId;

        if (userId.isEmpty()) {
            return;
        }
        if (!sid.isEmpty() && userId.equals(sid)) {
            return; // 自己发的消息（含机器人自己的回复回显），不回
        }

        if (type.isEmpty()) {
            type = groupId.isEmpty() ? "private" : "group";
        }
        if ("private".equals(type) && !cfg.privateEnabled) {
            return;
        }

        String raw = root.path("raw_message").asText("");
        if (raw.isEmpty()) {
            raw = rawFromSegments(root.path("message"));
        }
        if (raw.isBlank()) {
            return;
        }

        String prompt = unescape(stripCq(raw)).trim();
        boolean atMe = !sid.isEmpty() && raw.contains("[CQ:at,qq=" + sid + "]");
        String sessionKey = "group".equals(type) ? "group:" + groupId : "private:" + userId;

        // 合并转发（聊天记录）的正文不在消息里，只有一个 id，得再调一次接口去取。
        List<String> forwardIds = forwardIds(root, raw);
        String inlineForward = inlineForwardText(root);
        // 诊断：中转站各家送来的形状不一样，认不出来时得能看到原始报文，否则没法定位。
        if (hasForwardSegment(root) || raw.contains("forward")) {
            if (forwardIds.isEmpty() && inlineForward.isEmpty()) {
                log.warn("[合并转发] 检测到转发迹象但没解析出内容，原始消息: {}",
                        JsonHttp.abbreviate(root.toString()));
            } else {
                log.info("[合并转发] 这条消息带转发：id {} 个，内联 {} 字",
                        forwardIds.size(), inlineForward.length());
            }
        }

        boolean directed;
        String reason;
        if ("private".equals(type)) {
            directed = true;
            reason = "私聊";
        } else if (atMe) {
            directed = true;
            reason = "被 @";
        } else if (containsWakeWord(prompt)) {
            // 唤醒词是独立的一条路：不需要 @，也不受 group_require_at 影响。
            directed = true;
            reason = "命中唤醒词";
        } else if (!cfg.groupRequireAt) {
            directed = true;
            reason = "群聊未要求被叫";
        } else if (cfg.proactiveEnabled
                && (!cfg.proactiveOnQuestion || looksLikeQuestion(prompt))
                && allowProactive(groupId)) {
            directed = true;
            reason = cfg.proactiveOnQuestion ? "主动接话（像提问）" : "主动接话";
        } else {
            // 没叫她，但这条消息里带了转发的聊天记录 —— 先把内容取回来按会话存着。
            // 因为 QQ 里没法在同一条消息里既 @ 人又转发记录，她的问题通常是下一条。
            if (!forwardIds.isEmpty()) {
                chat.cacheForward(sessionKey, forwardIds);
            }
            return; // 群里没叫它就不理
        }
        // 别把 "[@]" 这种字面量当问题喂给模型；但也**不要**写成"消息里没有文字"这种元描述 ——
        // 实测那样模型会真的回"我这边显示空白哎"，很出戏。用一句自然的话带过。
        // 只处理"纯 @"：[图片]/[语音] 这类占位是有信息量的，要原样留着。
        if (prompt.isEmpty()) {
            prompt = "（对方没有说什么）";
        } else if (ONLY_AT.matcher(prompt).matches()) {
            prompt = "（对方只是 @ 了你，没有说话）";
        }

        String messageId = text(root.get("message_id"));
        String sender = senderName(root);

        // 有的实现把内容直接塞在段的 data.content 里，那就省一次调用。
        if (!inlineForward.isEmpty()) {
            prompt = prompt + "\n\n【对方转发了一段聊天记录，内容如下】\n" + inlineForward;
        }

        status.messageReceived();
        log.info("[收到] {} 来自 {}: {}{}", sessionKey, sender, oneLine(prompt),
                "私聊".equals(reason) ? "" : "   ← " + reason);

        final String fType = type;
        final String fGroup = groupId;
        final String fUser = userId;
        final String fMsgId = messageId;
        final String fSession = sessionKey;

        chat.submit(sessionKey, userId, sender, prompt, forwardIds, new ChatService.ReplySink() {
            @Override
            public void text(String text) {
                try {
                    sendReply(fType, fGroup, fUser, fMsgId, text);
                    log.info("[回复] {}: {}", fSession, oneLine(text));
                } catch (Exception e) {
                    status.failure("发送失败: " + e.getMessage());
                    log.error("[发送失败] {}: {}", fSession, e.getMessage());
                }
            }

            @Override
            public void image(Path file, String mimeType) {
                try {
                    sendImage(fType, fGroup, fUser, fMsgId, file);
                    log.info("[发图] {}: {}", fSession, file.getFileName());
                } catch (Exception e) {
                    status.failure("发图失败: " + e.getMessage());
                    log.error("[发图失败] {}: {}", fSession, e.getMessage());
                }
            }

            @Override
            public void failed(Throwable error) {
                log.error("[生成失败] {}: {}", fSession, error.toString());
            }
        });
    }

    private void sendReply(String type, String groupId, String userId, String msgId, String reply) throws Exception {
        for (String chunk : splitReply(reply)) {
            ObjectNode params = mapper.createObjectNode();
            String action;
            ArrayNode segments = params.putArray("message");
            if ("group".equals(type)) {
                action = "send_group_msg";
                putId(params, "group_id", groupId);
                if (msgId != null && !msgId.isBlank()) {
                    ObjectNode seg = segments.addObject();
                    seg.put("type", "reply");
                    seg.putObject("data").put("id", msgId);
                }
                if (userId != null && !userId.isBlank()) {
                    ObjectNode seg = segments.addObject();
                    seg.put("type", "at");
                    // 用 set 而不是 put：Jackson 2.17 起 ObjectNode.put(String, JsonNode) 已废弃。
                    seg.putObject("data").set("qq", numericOrText(userId));
                }
                ObjectNode seg = segments.addObject();
                seg.put("type", "text");
                seg.putObject("data").put("text", " " + chunk);
            } else {
                action = "send_private_msg";
                putId(params, "user_id", userId);
                ObjectNode seg = segments.addObject();
                seg.put("type", "text");
                seg.putObject("data").put("text", chunk);
            }
            api.call(action, params, 15000);
        }
    }

    /**
     * 发一张图。群里同样带上引用和 @，否则一张匿名图片很容易被当成刷屏。
     */
    private void sendImage(String type, String groupId, String userId, String msgId, Path file) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        ArrayNode segments = params.putArray("message");
        String action;
        if ("group".equals(type)) {
            action = "send_group_msg";
            putId(params, "group_id", groupId);
            if (msgId != null && !msgId.isBlank()) {
                ObjectNode seg = segments.addObject();
                seg.put("type", "reply");
                seg.putObject("data").put("id", msgId);
            }
            if (userId != null && !userId.isBlank()) {
                ObjectNode seg = segments.addObject();
                seg.put("type", "at");
                seg.putObject("data").set("qq", numericOrText(userId));
            }
        } else {
            action = "send_private_msg";
            putId(params, "user_id", userId);
        }
        ObjectNode image = segments.addObject();
        image.put("type", "image");
        image.putObject("data").put("file", imageFileValue(file));
        // 生成本身就要十几秒，发送再给宽一点。
        api.call(action, params, 60000);
    }

    /**
     * 交给 OneBot 的图片引用。
     * file 模式发本地 URI（消息小，中文路径也安全，因为 toUri 会百分号编码）；
     * base64 模式兼容性更好，但一条消息会大出三分之一。
     */
    private String imageFileValue(Path file) throws IOException {
        if ("base64".equalsIgnoreCase(cfg.imageSendMode)) {
            return "base64://" + Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        }
        return file.toUri().toString();
    }

    // ---------- 合并转发（聊天记录） ----------

    /** 消息里有没有转发段（各家实现的 type 名不完全一致）。 */
    private static boolean hasForwardSegment(JsonNode root) {
        JsonNode message = root.path("message");
        if (!message.isArray()) {
            return false;
        }
        for (JsonNode seg : message) {
            String segType = seg.path("type").asText("");
            if ("forward".equals(segType) || "node".equals(segType) || "xml".equals(segType)) {
                return true;
            }
        }
        return false;
    }

    /** 从 raw_message 和 message 段里收集需要单独去取的合并转发 id。 */
    private static List<String> forwardIds(JsonNode root, String raw) {
        List<String> ids = new ArrayList<>();
        Matcher m = FORWARD_CQ.matcher(raw == null ? "" : raw);
        while (m.find()) {
            String id = m.group(1).trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        JsonNode message = root.path("message");
        if (message.isArray()) {
            for (JsonNode seg : message) {
                if (!"forward".equals(seg.path("type").asText(""))) {
                    continue;
                }
                JsonNode data = seg.path("data");
                String id = data.path("id").asText("");
                if (id.isEmpty()) {
                    id = data.path("res_id").asText("");
                }
                if (!id.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** 有的实现把聊天记录内容直接塞在段的 data.content 里，那就不用再调接口。 */
    private static String inlineForwardText(JsonNode root) {
        JsonNode message = root.path("message");
        if (!message.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode seg : message) {
            if (!"forward".equals(seg.path("type").asText(""))) {
                continue;
            }
            String rendered = forwardText(seg.path("data").path("content"));
            if (!rendered.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(rendered);
            }
        }
        return cap(sb.toString());
    }

    /**
     * 取一段合并转发的正文。
     *
     * <p><b>只能在非 WebSocket 读线程上调用</b> —— {@code api.call} 要等响应，
     * 而响应是靠读线程处理的，在读线程里同步等它必然死锁。
     * 所以这个方法由 ChatService 的 worker 线程调用。
     */
    public String resolveForward(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("id", id.trim());
            JsonNode resp = api.call("get_forward_msg", params, 15000);
            String rendered = renderForward(resp.path("data"));
            if (rendered.isEmpty()) {
                log.warn("取到的合并转发是空的 id={}，原始响应: {}", id,
                        JsonHttp.abbreviate(resp.toString()));
            }
            return rendered;
        } catch (Exception e) {
            log.warn("取合并转发失败 id={}: {}", id, e.toString());
            return "";
        }
    }

    /** 把 {@code get_forward_msg} 的 data 渲染成"谁：说了什么"的分行文本。 */
    private String renderForward(JsonNode data) {
        JsonNode nodes = data.path("messages");
        if (!nodes.isArray() || nodes.isEmpty()) {
            nodes = data.path("message");
        }
        if (!nodes.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (JsonNode node : nodes) {
            // 两种常见结构：{"type":"node","data":{...}} 和 {"sender":{...},"message":[...]}
            JsonNode body = "node".equals(node.path("type").asText("")) ? node.path("data") : node;
            String who = body.path("nickname").asText("");
            if (who.isEmpty()) {
                who = body.path("sender").path("nickname").asText("");
            }
            if (who.isEmpty()) {
                who = body.path("user_id").asText(body.path("sender").path("user_id").asText(""));
            }
            String said = forwardText(body.path("content"));
            if (said.isEmpty()) {
                said = forwardText(body.path("message"));
            }
            if (said.isEmpty()) {
                continue;
            }
            if (count > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(who.isEmpty() ? "某人" : who).append("：").append(said);
            count++;
            if (count >= MAX_FORWARD_NODES) {
                sb.append("\n- （后面还有更多，已略去）");
                break;
            }
        }
        return cap(sb.toString());
    }

    /** 把一条转发节点里的内容渲染成一行文字。content/message 可能是数组，也可能是一串 CQ 码。 */
    private static String forwardText(JsonNode arr) {
        if (arr == null || arr.isMissingNode() || arr.isNull()) {
            return "";
        }
        if (arr.isTextual()) {
            return unescape(stripCq(arr.asText(""))).trim();
        }
        if (!arr.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode seg : arr) {
            String segType = seg.path("type").asText("");
            JsonNode data = seg.path("data");
            switch (segType) {
                case "text" -> sb.append(data.path("text").asText(""));
                case "image" -> sb.append("[图片]");
                case "face" -> sb.append("[表情]");
                case "record" -> sb.append("[语音]");
                case "video" -> sb.append("[视频]");
                case "at" -> sb.append("@").append(data.path("qq").asText(""));
                default -> {
                }
            }
        }
        return unescape(stripCq(sb.toString())).trim();
    }

    private static String cap(String text) {
        if (text == null || text.length() <= MAX_FORWARD_CHARS) {
            return text == null ? "" : text;
        }
        return text.substring(0, MAX_FORWARD_CHARS) + "\n…（聊天记录太长，后面已截断）";
    }

    // ---------- 鉴权 ----------

    private String authorize(ClientHandshake handshake) {
        String token = cfg.accessToken == null ? "" : cfg.accessToken.trim();
        if (token.isEmpty()) {
            return "服务端未配置 server.access_token";
        }
        String presented = null;
        String auth = handshake.getFieldValue("Authorization");
        if (auth != null && !auth.isBlank()) {
            presented = stripScheme(auth.trim());
        }
        if (presented == null) {
            presented = queryParam(handshake.getResourceDescriptor(), "access_token");
        }
        if (presented == null || presented.isEmpty()) {
            return "缺少 access_token";
        }
        if (!presented.equals(token)) {
            return "access_token 不匹配";
        }
        return null;
    }

    private static String stripScheme(String value) {
        int i = value.indexOf(' ');
        if (i < 0) {
            return value;
        }
        String scheme = value.substring(0, i);
        if (scheme.equalsIgnoreCase("bearer") || scheme.equalsIgnoreCase("token")) {
            return value.substring(i + 1).trim();
        }
        return value;
    }

    private static String queryParam(String resource, String key) {
        if (resource == null) {
            return null;
        }
        int q = resource.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : resource.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (key.equalsIgnoreCase(pair.substring(0, eq))) {
                String v = pair.substring(eq + 1);
                try {
                    return URLDecoder.decode(v, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return v;
                }
            }
        }
        return null;
    }

    // ---------- 消息文本处理 ----------

    private static String stripCq(String raw) {
        Matcher m = CQ.matcher(raw);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(raw, last, m.start());
            switch (m.group(1)) {
                case "image" -> sb.append("[图片]");
                case "face" -> sb.append("[表情]");
                case "record" -> sb.append("[语音]");
                case "video" -> sb.append("[视频]");
                case "forward" -> sb.append("[合并转发]");
                case "at" -> sb.append("[@]");
                case "reply" -> {
                }
                default -> {
                }
            }
            last = m.end();
        }
        sb.append(raw, last, raw.length());
        return sb.toString();
    }

    private static String unescape(String s) {
        return s.replace("&#91;", "[")
                .replace("&#93;", "]")
                .replace("&#44;", ",")
                .replace("&amp;", "&");
    }

    private static String rawFromSegments(JsonNode message) {
        if (message.isTextual()) {
            return message.asText();
        }
        if (!message.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode seg : message) {
            String segType = seg.path("type").asText("");
            JsonNode data = seg.path("data");
            if ("text".equals(segType)) {
                sb.append(data.path("text").asText(""));
            } else if ("at".equals(segType)) {
                sb.append("[CQ:at,qq=").append(data.path("qq").asText("")).append("]");
            } else if ("image".equals(segType)) {
                sb.append("[CQ:image,file=x]");
            }
        }
        return sb.toString();
    }

    private boolean containsWakeWord(String text) {
        for (String word : cfg.wakeWords) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 便宜的启发式：这条正文像不像在提问。只认形式不认语义，也**不额外调模型** ——
     * 这是"不用 @ 也能唤醒"的廉价版本，代价是判断不如评分模型准。
     */
    static boolean looksLikeQuestion(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.endsWith("?") || t.endsWith("？")) {
            return true;
        }
        return QUESTION.matcher(t).matches();
    }

    /**
     * 主动接话的限流：同一个群要有冷却间隔，且每小时不超过上限。
     * 返回 true 表示这次记账了，可以开口。
     */
    private boolean allowProactive(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = proactiveLog.computeIfAbsent(groupId, k -> new ArrayDeque<>());
        synchronized (times) {
            Long last = times.peekLast();
            if (last != null && now - last < cfg.proactiveCooldownSeconds * 1000L) {
                return false;
            }
            while (!times.isEmpty() && now - times.peekFirst() > 3_600_000L) {
                times.pollFirst();
            }
            if (times.size() >= cfg.proactiveMaxPerHour) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private static List<String> splitReply(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (t.length() <= MAX_TEXT_PER_MESSAGE) {
            return List.of(t);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : t.split("\n")) {
            if (line.length() > MAX_TEXT_PER_MESSAGE) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < line.length(); i += MAX_TEXT_PER_MESSAGE) {
                    out.add(line.substring(i, Math.min(line.length(), i + MAX_TEXT_PER_MESSAGE)));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + line.length() + 1 > MAX_TEXT_PER_MESSAGE) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(line);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    // ---------- 小工具 ----------

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.asText("");
    }

    private static void putId(ObjectNode target, String key, String value) {
        target.set(key, numericOrText(value));
    }

    private static JsonNode numericOrText(String value) {
        if (value != null && !value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
            try {
                return new ObjectMapper().getNodeFactory().numberNode(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // 超出 long 就当字符串发
            }
        }
        return new ObjectMapper().getNodeFactory().textNode(value == null ? "" : value);
    }

    private static String senderName(JsonNode root) {
        JsonNode sender = root.path("sender");
        String card = sender.path("card").asText("");
        if (!card.isBlank()) {
            return card;
        }
        String nickname = sender.path("nickname").asText("");
        if (!nickname.isBlank()) {
            return nickname;
        }
        String uid = text(root.get("user_id"));
        return uid.isEmpty() ? "?" : uid;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
