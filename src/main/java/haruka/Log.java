package haruka;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 极简日志：写一行到 stdout，同时在内存里留一份环形缓冲，供控制台实时查看。
 *
 * <p>项目自己的日志走这里而不是 slf4j：控制台需要拿到日志流，而 slf4j-simple 没有可挂的
 * appender，要么再引一个日志框架，要么自己写。这 150 行让"日志顺便进缓冲"变成天然行为
 * 而不是额外接线。
 *
 * <p>注意 slf4j 本身没有去掉 —— Java-WebSocket 1.5.x 依赖 slf4j-api，需要一个绑定才能
 * 保留它自己的内部诊断日志。那条路走 slf4j-simple，级别在 {@code Main} 里压到 warn。
 *
 * <p>调用形状和 slf4j 一致（{@code Log.get(X.class)} + {@code "{}"} 占位符），
 * 所以替换原有调用几乎不用改代码。
 */
public final class Log {

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    /** 一条日志。seq 单调递增，控制台用它做断点续传。 */
    public record Entry(long seq, String time, Level level, String name, String message) {
    }

    /** 内存里保留多少条历史给控制台。 */
    private static final int CAPACITY = 800;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final PrintStream OUT = System.out;

    private static final Object LOCK = new Object();
    private static final Deque<Entry> BUFFER = new ArrayDeque<>();
    private static final List<Consumer<Entry>> SUBSCRIBERS = new CopyOnWriteArrayList<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static volatile boolean debugEnabled = false;

    private final String name;

    private Log(String name) {
        this.name = name;
    }

    public static Log get(Class<?> type) {
        return new Log(type.getSimpleName());
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /** 控制台订阅。返回取消订阅的句柄，SSE 断开时调用。 */
    public static Runnable subscribe(Consumer<Entry> listener) {
        SUBSCRIBERS.add(listener);
        return () -> SUBSCRIBERS.remove(listener);
    }

    /** 环形缓冲的当前快照，按 seq 升序。 */
    public static List<Entry> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(BUFFER);
        }
    }

    /** seq 大于 after 的那部分历史；控制台重连时用它补齐断档。 */
    public static List<Entry> snapshotAfter(long after) {
        synchronized (LOCK) {
            List<Entry> out = new ArrayList<>();
            for (Entry entry : BUFFER) {
                if (entry.seq() > after) {
                    out.add(entry);
                }
            }
            return out;
        }
    }

    /** 当前最大 seq；缓冲区为空时返回 0。 */
    public static long latestSeq() {
        synchronized (LOCK) {
            Entry last = BUFFER.peekLast();
            return last == null ? 0 : last.seq();
        }
    }

    public void debug(String format, Object... args) {
        if (debugEnabled) {
            write(Level.DEBUG, format, args);
        }
    }

    public void info(String format, Object... args) {
        write(Level.INFO, format, args);
    }

    public void warn(String format, Object... args) {
        write(Level.WARN, format, args);
    }

    public void error(String format, Object... args) {
        write(Level.ERROR, format, args);
    }

    private void write(Level level, String format, Object... args) {
        Object[] values = args == null ? new Object[0] : args;

        // 和 slf4j 一致：只有当占位符比参数少时，尾部那个异常才当作堆栈处理。
        Throwable thrown = null;
        if (values.length > 0 && values[values.length - 1] instanceof Throwable t
                && countPlaceholders(format) < values.length) {
            thrown = t;
            values = Arrays.copyOf(values, values.length - 1);
        }

        Entry entry = new Entry(SEQ.incrementAndGet(), LocalTime.now().format(TIME), level,
                name, render(format, values));

        synchronized (LOCK) {
            BUFFER.addLast(entry);
            while (BUFFER.size() > CAPACITY) {
                BUFFER.removeFirst();
            }
        }

        OUT.println(entry.time() + " " + label(level) + " " + name + " - " + entry.message());
        if (thrown != null) {
            thrown.printStackTrace(OUT);
        }

        for (Consumer<Entry> subscriber : SUBSCRIBERS) {
            try {
                subscriber.accept(entry);
            } catch (RuntimeException ignored) {
                // 控制台断开或写入失败不该影响机器人的主流程
            }
        }
    }

    private static String label(Level level) {
        return switch (level) {
            case DEBUG -> "DEBUG";
            case INFO -> "INFO ";
            case WARN -> "WARN ";
            case ERROR -> "ERROR";
        };
    }

    static int countPlaceholders(String format) {
        if (format == null) {
            return 0;
        }
        int count = 0;
        for (int i = format.indexOf("{}"); i >= 0; i = format.indexOf("{}", i + 2)) {
            count++;
        }
        return count;
    }

    /** 支持 slf4j 风格的 {} 占位符；多余参数追加在末尾，避免信息被悄悄丢掉。 */
    static String render(String format, Object... args) {
        if (format == null) {
            return "null";
        }
        if (args == null || args.length == 0) {
            return format;
        }
        StringBuilder sb = new StringBuilder(format.length() + 32);
        int argIndex = 0;
        int cursor = 0;
        while (cursor < format.length()) {
            int brace = format.indexOf("{}", cursor);
            if (brace < 0 || argIndex >= args.length) {
                sb.append(format, cursor, format.length());
                break;
            }
            sb.append(format, cursor, brace);
            sb.append(stringify(args[argIndex++]));
            cursor = brace + 2;
        }
        if (argIndex < args.length) {
            sb.append(" [extra:");
            for (int i = argIndex; i < args.length; i++) {
                sb.append(' ').append(stringify(args[i]));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Throwable t) {
            String message = t.getMessage();
            return message == null ? t.getClass().getSimpleName()
                    : t.getClass().getSimpleName() + ": " + message;
        }
        return String.valueOf(value);
    }

    /** 取最内层异常的消息，避免满屏 "java.lang.RuntimeException: ..." 套娃。 */
    public static String rootMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
