package haruka;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 盯着配置文件和人设文件，被手工改动后自动热重载。
 *
 * <p>这是"完全可以靠手改文件来配置"的关键一环：控制台是可选层，改了文件不用重启，
 * 也不用经过控制台。人设文件也算配置 —— 你编辑 haruka.md 保存，她就换了性格。
 *
 * <p>两个细节保证它不添乱：
 * <ul>
 *   <li>控制台自己写配置文件时也会更新时间戳，所以那次写入不会被当成外部改动重复加载；</li>
 *   <li>文件刚被保存时可能还没写完，所以只处理"至少安静了 400ms"的版本，
 *       配置解析失败就保留原配置并记下时间戳，避免每轮都刷同一条警告。</li>
 * </ul>
 */
public final class ConfigWatcher {

    private static final Log log = Log.get(ConfigWatcher.class);
    private static final long INTERVAL_MS = 2000;
    private static final long SETTLE_MS = 400;

    private final ConfigStore store;
    private final RuntimeStatus status;
    private final Persona persona;

    private volatile boolean running = true;
    private volatile long personaSeen = Long.MIN_VALUE;
    private Thread thread;

    public ConfigWatcher(ConfigStore store, RuntimeStatus status, Persona persona) {
        this.store = store;
        this.status = status;
        this.persona = persona;
    }

    public void start() {
        thread = new Thread(this::loop, "haruka-config-watch");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                checkConfig();
                checkPersona();
            } catch (RuntimeException e) {
                log.debug("检查文件时出错: {}", e.toString());
            }
        }
    }

    private void checkConfig() {
        Path path = store.path();
        if (!Files.isRegularFile(path)) {
            return;
        }

        long modified;
        try {
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return;
        }

        if (modified == store.lastSeenModified()) {
            return; // 已知状态
        }
        if (System.currentTimeMillis() - modified < SETTLE_MS) {
            return; // 可能还在写，下一轮再看
        }

        try {
            long at = store.reload();
            status.reloadedAt = at;
            log.info("检测到 {} 变化，已重新加载配置", path.getFileName());
        } catch (Exception e) {
            log.warn("配置文件改动无法解析，已保留原配置: {}", Log.rootMessage(e));
            store.markSeen(modified);
        }
    }

    /** 人设文件同样是"手改即生效"的配置。它解析不了也没关系，最坏就是人设没换。 */
    private void checkPersona() {
        if (persona == null) {
            return;
        }
        Path path = persona.path();
        if (path == null) {
            return;
        }

        long modified;
        try {
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return;
        }

        if (personaSeen == Long.MIN_VALUE) {
            personaSeen = modified; // 首次只是记下基线
            return;
        }
        if (modified == personaSeen) {
            return;
        }
        if (System.currentTimeMillis() - modified < SETTLE_MS) {
            return;
        }

        persona.invalidate();
        personaSeen = modified;
        log.info("检测到人设文件 {} 变化，已重新加载（{} 字）",
                path.getFileName(), persona.fileText().length());
    }
}
