package haruka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置文件的读写。
 *
 * <p>保存时只替换改动的那一行 {@code key=value}，注释、键顺序以及用户自己加的行全部原样保留 ——
 * 配置文件是给人读、给人手改的，被控制台保存一次就丢掉注释是不能接受的。
 *
 * <p>{@link #lastSeenModified()} 记录"已经消化过的文件时间戳"，写回和重载都会更新它，
 * 这样 {@link ConfigWatcher} 不会把控制台自己那次写入再当成一次外部改动重复加载。
 */
public final class ConfigStore {

    private static final String NEW_SECTION = "# --- 由控制台写入 ---";

    private final Path path;
    private final Config config;
    private volatile long lastSeenModified;

    public ConfigStore(String file, Config config) {
        this.path = Path.of(file).toAbsolutePath();
        this.config = config;
        this.lastSeenModified = readModified();
    }

    public Path path() {
        return path;
    }

    public Config config() {
        return config;
    }

    public long lastSeenModified() {
        return lastSeenModified;
    }

    /** 记下这个时间戳为"已消化"，避免对同一版文件反复告警。 */
    public void markSeen(long modified) {
        this.lastSeenModified = modified;
    }

    public synchronized long readModified() {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time == null ? -1 : time.toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 写入若干 key，然后热更新运行中的配置。
     *
     * @return 实际写入的 key
     */
    public synchronized Set<String> apply(Map<String, String> changes) throws IOException {
        if (changes.isEmpty()) {
            return Set.of();
        }

        List<String> lines = Files.isRegularFile(path)
                ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
                : new ArrayList<>();

        Set<String> remaining = new LinkedHashSet<>(changes.keySet());
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = changes.get(key);
            if (value == null) {
                continue;
            }
            lines.set(i, key + "=" + escape(value));
            remaining.remove(key);
        }

        if (!remaining.isEmpty()) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add(NEW_SECTION);
            for (String key : remaining) {
                lines.add(key + "=" + escape(changes.get(key)));
            }
        }

        Files.write(path, lines, StandardCharsets.UTF_8);
        config.copyFrom(Config.load(path.toString()));
        lastSeenModified = readModified();
        return new LinkedHashSet<>(changes.keySet());
    }

    /**
     * 从磁盘重新读一遍并热更新。{@link ConfigWatcher} 和 {@code POST /api/reload} 都走这里，
     * 所以"手改文件"和"控制台保存"最终收敛到同一条路径上。
     *
     * @return 重载完成的时间戳
     */
    public synchronized long reload() throws IOException {
        config.copyFrom(Config.load(path.toString()));
        lastSeenModified = readModified();
        return System.currentTimeMillis();
    }

    /** 当前配置的文本形式，供控制台直接展示。文件不存在时返回空串。 */
    public synchronized String readText() throws IOException {
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    /**
     * properties 格式的值转义。反斜杠和换行必须转义，否则 {@code Properties.load} 会解读成别的东西；
     * 行首空格也要转义，否则会被当作分隔符后的空白吃掉。
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        if (value.startsWith(" ")) {
            sb.append('\\');
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
