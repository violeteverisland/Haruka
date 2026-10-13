package haruka;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行状态。网络线程、聊天线程和控制台线程会并发读写，所以计数值用原子量、
 * 其余字段一律 volatile。
 */
public final class RuntimeStatus {

    public volatile long startedAt = System.currentTimeMillis();
    public volatile boolean oneBotConnected;
    public volatile String selfId = "";
    public volatile String nickname = "";
    public volatile String remoteAddress = "";
    public volatile long connectedAt;
    public volatile long lastMessageAt;
    public volatile String lastError = "";

    /** 当前进程是否处于"只跑控制台、不跑机器人本体"模式。 */
    public volatile boolean webOnly;

    /** 最近一次从磁盘重新加载配置的时间戳；0 表示从未。 */
    public volatile long reloadedAt;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong replied = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public void connected(String selfId, String remoteAddress) {
        this.selfId = selfId == null ? "" : selfId;
        this.remoteAddress = remoteAddress == null ? "" : remoteAddress;
        this.connectedAt = System.currentTimeMillis();
        this.oneBotConnected = true;
        this.lastError = "";
    }

    public void disconnected(String reason) {
        this.oneBotConnected = false;
        this.nickname = "";
        if (reason != null && !reason.isBlank()) {
            this.lastError = reason;
        }
    }

    public void loginInfo(String nickname) {
        this.nickname = nickname == null ? "" : nickname;
    }

    public void messageReceived() {
        received.incrementAndGet();
        lastMessageAt = System.currentTimeMillis();
    }

    public void messageReplied() {
        replied.incrementAndGet();
    }

    public void failure(String message) {
        failed.incrementAndGet();
        lastError = message == null ? "" : message;
    }

    public long received() {
        return received.get();
    }

    public long replied() {
        return replied.get();
    }

    public long failed() {
        return failed.get();
    }

    public long uptimeSeconds() {
        return Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
    }
}
