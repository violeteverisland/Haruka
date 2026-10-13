package haruka;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 长期记忆的落盘存储（SQLite）。
 *
 * <p><b>为什么强制 {@code journal_mode=DELETE} 而不是更快的 WAL：</b>
 * WAL 会额外产生 {@code .db-wal} / {@code .db-shm} 两个边车文件，"把数据库拷到另一台机器"
 * 就变成"记得拷三个文件，漏一个就丢最近的数据"。DELETE 模式下全部数据都在单个 .db 里，
 * 拷一个文件就够了 —— 这是这个项目明确要的性质。聊天机器人的写入量根本不值得为此换 WAL。
 *
 * <p>连接只有一条并在方法上同步：写入频率极低，简单可靠优先。
 * 打不开数据库时不会让机器人挂掉，而是退化成"记忆功能不可用"。
 */
public final class MemoryStore implements AutoCloseable {

    private static final Log log = Log.get(MemoryStore.class);
    private static final int SCHEMA_VERSION = 1;

    /** 一条记忆。scope 形如 {@code private:123456} / {@code group:987654} / {@code global}。 */
    public record Memory(long id, String scope, String subject, String content, String source,
                         long createdAt, long updatedAt, long hits, boolean active) {
    }

    private final Path path;
    private Connection connection;
    private volatile boolean available;

    public MemoryStore(String dbPath) {
        this.path = Path.of(dbPath == null || dbPath.isBlank() ? "data/haruka.db" : dbPath.trim())
                .toAbsolutePath().normalize();
    }

    public Path path() {
        return path;
    }

    public boolean available() {
        return available;
    }

    /** 打开并建表。失败只记日志并把自身标记为不可用，不影响机器人其余部分。 */
    public synchronized void open() {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = connection.createStatement()) {
                // 单文件的关键：不开 WAL。
                st.execute("PRAGMA journal_mode=DELETE");
                st.execute("PRAGMA synchronous=FULL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            createSchema();
            available = true;
            log.info("长期记忆库: {}（{} 条记忆）", path, count());
        } catch (Exception e) {
            available = false;
            connection = null;
            log.error("打开记忆库失败，记忆功能本次不可用: {} ({})", path, Log.rootMessage(e));
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS memories (
                      id          INTEGER PRIMARY KEY AUTOINCREMENT,
                      scope       TEXT    NOT NULL,
                      subject     TEXT    NOT NULL DEFAULT '',
                      content     TEXT    NOT NULL,
                      fingerprint TEXT    NOT NULL,
                      source      TEXT    NOT NULL DEFAULT 'chat',
                      created_at  INTEGER NOT NULL,
                      updated_at  INTEGER NOT NULL,
                      hits        INTEGER NOT NULL DEFAULT 0,
                      active      INTEGER NOT NULL DEFAULT 1
                    )""");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_fp ON memories(fingerprint)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_memories_scope ON memories(scope, active, updated_at DESC)");
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        }
        putMeta("schema_version", String.valueOf(SCHEMA_VERSION));
        putMetaIfAbsent("created_at", String.valueOf(System.currentTimeMillis()));
    }

    private void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private void putMetaIfAbsent(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO meta(k, v) VALUES(?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public synchronized String meta(String key, String fallback) {
        if (!available) {
            return fallback;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : fallback;
            }
        } catch (SQLException e) {
            return fallback;
        }
    }

    /**
     * 记一条。已存在同一条（同 scope 且内容规范化后一致）就只更新时间和来源，返回原有 id。
     *
     * @return 记忆 id；库不可用或内容为空时返回 -1
     */
    public synchronized long add(String scope, String subject, String content, String source) {
        if (!available) {
            return -1;
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return -1;
        }
        String cleanScope = scope == null || scope.isBlank() ? "global" : scope.trim();
        String cleanSubject = subject == null ? "" : subject.trim();
        String cleanSource = source == null || source.isBlank() ? "chat" : source.trim();
        String fingerprint = fingerprint(cleanScope, text);
        long now = System.currentTimeMillis();

        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM memories WHERE fingerprint = ?")) {
                ps.setString(1, fingerprint);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        try (PreparedStatement up = connection.prepareStatement(
                                "UPDATE memories SET updated_at = ?, active = 1 WHERE id = ?")) {
                            up.setLong(1, now);
                            up.setLong(2, id);
                            up.executeUpdate();
                        }
                        return id;
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO memories(scope, subject, content, fingerprint, source, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, cleanScope);
                ps.setString(2, cleanSubject);
                ps.setString(3, text);
                ps.setString(4, fingerprint);
                ps.setString(5, cleanSource);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1;
                }
            }
        } catch (SQLException e) {
            log.warn("写入记忆失败: {}", Log.rootMessage(e));
            return -1;
        }
    }

    /** 给某个会话取要注入 prompt 的记忆：本会话的 + 全局的，按命中次数和最近更新排序。 */
    public synchronized List<Memory> forInjection(String scope, int limit) {
        if (!available || limit <= 0) {
            return List.of();
        }
        String s = scope == null || scope.isBlank() ? "global" : scope.trim();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, scope, subject, content, source, created_at, updated_at, hits, active
                FROM memories
                WHERE active = 1 AND (scope = ? OR scope = 'global')
                ORDER BY hits DESC, updated_at DESC
                LIMIT ?""")) {
            ps.setString(1, s);
            ps.setInt(2, limit);
            return readAll(ps);
        } catch (SQLException e) {
            log.warn("读取记忆失败: {}", Log.rootMessage(e));
            return List.of();
        }
    }

    /** 列出记忆。scope 为空表示不过滤；query 为空表示不按内容过滤。 */
    public synchronized List<Memory> list(String scope, String query, int limit) {
        if (!available) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, scope, subject, content, source, created_at, updated_at, hits, active "
                        + "FROM memories WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope = ?");
            args.add(scope.trim());
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (content LIKE ? OR subject LIKE ?)");
            String like = "%" + query.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        args.add(limit <= 0 ? 200 : Math.min(limit, 2000));

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.get(i);
                if (arg instanceof Integer n) {
                    ps.setInt(i + 1, n);
                } else {
                    ps.setString(i + 1, String.valueOf(arg));
                }
            }
            return readAll(ps);
        } catch (SQLException e) {
            log.warn("列出记忆失败: {}", Log.rootMessage(e));
            return List.of();
        }
    }

    /** 记录一次使用（让常被用到的记忆排前面）。 */
    public synchronized void touch(List<Long> ids) {
        if (!available || ids == null || ids.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE memories SET hits = hits + 1 WHERE id = ?")) {
            for (Long id : ids) {
                ps.setLong(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.debug("更新记忆命中次数失败: {}", Log.rootMessage(e));
        }
    }

    public synchronized boolean update(long id, String subject, String content) {
        if (!available) {
            return false;
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE memories SET subject = ?, content = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, subject == null ? "" : subject.trim());
            ps.setString(2, text);
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("修改记忆失败: {}", Log.rootMessage(e));
            return false;
        }
    }

    public synchronized boolean delete(long id) {
        if (!available) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM memories WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("删除记忆失败: {}", Log.rootMessage(e));
            return false;
        }
    }

    /**
     * 某个 scope 超过上限时淘汰多余的：优先保留常被用到的，其次是最近更新的。
     * 不这么做的话，聊得多的会话会把记忆无限堆下去。
     *
     * @return 淘汰掉的条数
     */
    public synchronized int prune(String scope, int max) {
        if (!available || max <= 0) {
            return 0;
        }
        String s = scope == null || scope.isBlank() ? "global" : scope.trim();
        try (PreparedStatement ps = connection.prepareStatement("""
                DELETE FROM memories WHERE scope = ? AND id NOT IN (
                  SELECT id FROM memories WHERE scope = ?
                  ORDER BY hits DESC, updated_at DESC LIMIT ?
                )""")) {
            ps.setString(1, s);
            ps.setString(2, s);
            ps.setInt(3, max);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("淘汰旧记忆失败: {}", Log.rootMessage(e));
            return 0;
        }
    }

    public synchronized long count() {
        if (!available) {
            return 0;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM memories WHERE active = 1")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 当前所有 scope，按记忆条数降序。控制台用它渲染会话列表。 */
    public synchronized List<String> scopes() {
        if (!available) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT scope, COUNT(*) c FROM memories WHERE active = 1 GROUP BY scope ORDER BY c DESC")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.debug("列出 scope 失败: {}", Log.rootMessage(e));
        }
        return out;
    }

    private static List<Memory> readAll(PreparedStatement ps) throws SQLException {
        List<Memory> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Memory(
                        rs.getLong("id"),
                        rs.getString("scope"),
                        rs.getString("subject"),
                        rs.getString("content"),
                        rs.getString("source"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at"),
                        rs.getLong("hits"),
                        rs.getInt("active") != 0));
            }
        }
        return out;
    }

    /**
     * 去重用的规范化：小写、去空白、去掉结尾的标点和语气助词。
     *
     * <p>最后那条是关键：模型抽取同一件事时措辞常差一个字（"……已经学了两个月" 和
     * "……已经学了两个月了"），末尾助词不抹掉就会被记成两条重复项。
     *
     * <p>只抹**末尾**、且只抹这些明显的语气字，是为了不把 "喜欢猫" 和 "不喜欢猫"
     * 这类语义相反的句子混为一谈 —— 那种错误比留一条重复项严重得多。
     */
    static String normalize(String content) {
        if (content == null) {
            return "";
        }
        return content.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[。．.,，、;；:：!！?？~～\"'“”‘’()（）【】\\[\\]]+$", "")
                .replaceAll("[了呢吧啊呀啦嘛哦噢咯嘞]+$", "")
                .replaceAll("[。．.,，、;；:：!！?？~～]+$", "");
    }

    /**
     * 内容指纹，用来去重。
     */
    static String fingerprint(String scope, String content) {
        String normalized = normalize(content);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((scope + "\u0000" + normalized).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((scope + normalized).hashCode());
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try (Statement st = connection.createStatement()) {
                // 收尾时把改动落进主文件，确保 .db 单独拷走就是完整的。
                st.execute("PRAGMA optimize");
            } catch (SQLException ignored) {
                // 关闭阶段不再处理
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 同上
            }
            connection = null;
        }
        available = false;
    }
}
