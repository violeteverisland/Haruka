package haruka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 往 OneBot 接入端发 API 请求（send_private_msg / send_group_msg / get_login_info ...）。
 * OneBot 的请求-响应靠 echo 字段配对，这里就用它把异步响应变回同步调用。
 */
public final class OneBotApi {

    private final ObjectMapper mapper;
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile WebSocket conn;

    public OneBotApi(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void bind(WebSocket c) {
        this.conn = c;
    }

    /**
     * 只在这一条连接确实是当前绑定的连接时才解绑并中断在途请求。
     * 否则旧连接延迟关闭会把新连接上正等 echo 的请求全部打断。
     */
    public void unbind(WebSocket c) {
        if (this.conn != c) {
            return;
        }
        this.conn = null;
        failAll("OneBot 连接已断开");
    }

    public boolean isConnected() {
        WebSocket c = conn;
        return c != null && c.isOpen();
    }

    /** 是本次调用的响应就消费掉并返回 true；是事件返回 false。 */
    public boolean onResponse(JsonNode root) {
        if (root.has("post_type")) {
            return false;
        }
        String echo = root.path("echo").asText("");
        if (!echo.isEmpty()) {
            CompletableFuture<JsonNode> f = pending.remove(echo);
            if (f != null) {
                f.complete(root);
                return true;
            }
        }
        // 没有 echo 但有 retcode，也是响应而不是事件
        return root.has("retcode");
    }

    public JsonNode call(String action, ObjectNode params, long timeoutMs) throws Exception {
        WebSocket c = conn;
        if (c == null || !c.isOpen()) {
            throw new IllegalStateException("OneBot 尚未连接");
        }
        String echo = "haruka-" + seq.incrementAndGet();
        ObjectNode req = mapper.createObjectNode();
        req.put("action", action);
        req.set("params", params);
        req.put("echo", echo);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(echo, future);
        try {
            c.send(req.toString());
            JsonNode resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (resp.path("retcode").asInt(-1) != 0) {
                throw new RuntimeException(action + " 返回失败: " + resp);
            }
            return resp;
        } finally {
            pending.remove(echo);
        }
    }

    private void failAll(String reason) {
        for (String key : pending.keySet()) {
            CompletableFuture<JsonNode> f = pending.remove(key);
            if (f != null) {
                f.completeExceptionally(new IllegalStateException(reason));
            }
        }
    }
}
