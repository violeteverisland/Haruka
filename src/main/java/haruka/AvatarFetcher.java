package haruka;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 抓 QQ 头像。
 *
 * <p>关键事实：OneBot v11 的消息事件里**只有文字**，不含头像；但 QQ 头像地址是
 * **纯拼接的固定地址**，不用调任何 OneBot 接口：
 * <ul>
 *   <li>个人：{@code https://q1.qlogo.cn/g?b=qq&nk=<QQ号>&s=<尺寸>}</li>
 *   <li>群：{@code https://p.qlogo.cn/gh/<群号>/<群号>/<尺寸>}</li>
 * </ul>
 *
 * <p>取到字节之后作为图片附件交给多模态模型，她这才算真的"看得见头像"。
 *
 * <p>头像永远只是锦上添花：取不到就返回 null，绝不能让回复因此失败。
 */
public final class AvatarFetcher {

    private static final Log log = Log.get(AvatarFetcher.class);

    private final Config cfg;

    public AvatarFetcher(Config cfg) {
        this.cfg = cfg;
    }

    static String memberUrl(String qq, int size) {
        return "https://q1.qlogo.cn/g?b=qq&nk=" + qq + "&s=" + size;
    }

    static String groupUrl(String groupId, int size) {
        return "https://p.qlogo.cn/gh/" + groupId + "/" + groupId + "/" + size;
    }

    /** 某人的头像本地文件；取不到返回 null。 */
    public Path member(String qq) {
        if (qq == null || qq.isBlank() || !qq.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return fetch(memberUrl(qq.trim(), cfg.avatarSize), "u" + qq.trim());
    }

    /** 某个群的头像本地文件；取不到返回 null。 */
    public Path group(String groupId) {
        if (groupId == null || groupId.isBlank() || !groupId.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return fetch(groupUrl(groupId.trim(), cfg.avatarSize), "g" + groupId.trim());
    }

    private Path fetch(String url, String name) {
        if (!cfg.avatarEnabled) {
            return null;
        }
        try {
            Path dir = Path.of(cfg.avatarCacheDir == null || cfg.avatarCacheDir.isBlank()
                    ? "data/avatars" : cfg.avatarCacheDir.trim()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".jpg");

            if (Files.isRegularFile(file)) {
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
                if (age < cfg.avatarCacheMinutes * 60_000L) {
                    return file;
                }
            }

            byte[] data = JsonHttp.getBytes(url, cfg.avatarTimeoutSeconds);
            if (data.length == 0) {
                return null;
            }
            Files.write(file, data);
            log.debug("取回头像 {}（{} KB）", name, data.length / 1024);
            return file;
        } catch (Exception e) {
            // 头像是可选的，拿不到就当她这次没看见图，别影响回复
            log.debug("取头像失败 {}: {}", url, Log.rootMessage(e));
            return Files.isRegularFile(Path.of(cfg.avatarCacheDir, name + ".jpg"))
                    ? Path.of(cfg.avatarCacheDir, name + ".jpg") : null;
        }
    }
}
