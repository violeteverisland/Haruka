# haruka

一个极简的 Java QQ 聊天机器人。接 OneBot v11（反向 WebSocket），单 jar，自带一个独立的浏览器控制台。

支持私聊和群聊、人设文件、长期记忆、画图、以及 openai / gemini 两种模型协议。

## 需要什么

| | |
| --- | --- |
| **JDK 17+** | 运行和构建都要 |
| **Maven** | 构建要 |
| **OneBot v11 实现** | 用来连 QQ，例如 SnowLuma |

## 1. 构建

```bat
build.bat
```
```sh
./build.sh
```

产物是 `target/haruka.jar`。也可以直接：

```sh
mvn -DskipTests clean package
```

> 仓库里**不提供编译好的 jar**，请自行构建。构建脚本会自己找 JDK 和 Maven；找不到就设置 `JAVA_HOME`，或把 `mvn` 加进 PATH。

## 2. 配置

```bat
copy config.example.properties config.properties
```
```sh
cp config.example.properties config.properties
```

模板是 `config.example.properties`，**你自己那份是 `config.properties`**（已在 `.gitignore` 里）。`run.bat` / `run.sh` 不带参数时就读它。

### 必须改

```properties
llm.api_key=你的模型 API Key
```

### 按需改

`llm.provider` 和 `llm.base_url` 要配套：

| `llm.provider` | `llm.base_url` 填到哪一层 |
| --- | --- |
| `openai` | 到 `/v1`，例：`https://api.openai.com/v1` |
| `gemini` | 主机加端口即可，例：`http://127.0.0.1:8080`；贴 `…/v1beta/models` 也行 |

`llm.model` **不用猜** —— 启动后在控制台「模型」页点「拉取模型列表」，从列表里点一个即可（这一步不依赖 model，也不需要先保存）。

`server.access_token` **留空就行**：首次启动会自动生成，写回配置文件并打印在启动日志里。把它抄进 SnowLuma。

### 人设

```bat
copy persona.example.md haruka.md
```

然后在配置里指过去：

```properties
llm.system_prompt_file=haruka.md
```

改这个文件保存即生效，不用重启；控制台「人设」页也能直接编辑。

## 3. 启动

```bat
run.bat
```
```sh
./run.sh
```

| 跑法 | 怎么做 |
| --- | --- |
| 机器人 + 控制台 | `run.bat` / `./run.sh` |
| 只跑机器人（不开任何 HTTP 端口） | 配置里 `web.enabled=false` |
| 只跑控制台（不连 QQ） | `run.bat config.properties --web-only` |
| 指定配置文件 | `run.bat my.properties` |
| 调试日志 | 追加 `--debug` |

**改配置不用重启**：程序每 2 秒检查配置文件，改了自动重载。例外是这几项 —— `server.host`、`server.port`、`bot.workers`、`web.host`、`web.port`、`web.enabled`、`memory.enabled`、`memory.db_path`。

## 4. 连上 QQ

1. 先启动 haruka。
2. 在 SnowLuma 里加一个**反向 WebSocket**连接：
   - 地址：`ws://127.0.0.1:18080/onebot/v11/ws`（SnowLuma 在另一台机器上就换成 haruka 那台的局域网 IP）
   - Access Token：填启动日志里打印的那一串
3. 用**另一个 QQ 号**私聊机器人，或者在群里 @ 它。

> 地址和 token 都在控制台第一页「连接 QQ」卡片里，可以直接复制。
>
> **必须用另一个 QQ 号测试** —— 机器人自己发的消息不会触发回复。

群聊里她会在下面任一条件成立时开口：**被 @**、**正文命中唤醒词**（`bot.wake_words`，默认 `Haruka,haruka`）、**开启主动接话后像提问的消息**（`bot.proactive_enabled=true`）。

## 5. 控制台

浏览器打开 **`http://127.0.0.1:18081`**。

- 密钥框**留空**（除非你在配置里设了 `web.secret`）。
- 左侧分区：概览 / 连接 / 模型 / 人设 / 行为 / 能力 / 记忆 / 控制台。
- 页面已经打进 jar，不需要单独部署；想换界面就把 `webui/index.html` 放到运行目录。

想从别的机器打开控制台，把 `web.host` 改成 `0.0.0.0` —— **但必须先设 `web.secret`**，否则程序拒绝启动。

## 配置速查

```properties
# --- 服务端（SnowLuma 连这里）---
server.host=0.0.0.0            # 只本机连就写 127.0.0.1
server.port=18080
server.access_token=           # 留空，首次启动自动生成

# --- 模型 ---
llm.provider=openai            # openai | gemini
llm.base_url=https://api.openai.com/v1
llm.api_key=CHANGE_ME
llm.model=gpt-4o-mini
llm.system_prompt_file=        # 人设文件，留空则用下面的内联文本
llm.system_prompt=你是一个 QQ 聊天机器人，说话简短自然。
llm.history_limit=20           # 每个会话保留多少条上下文
llm.max_tokens=2048            # Gemini 的思考 token 也算在里面，给太小会空回复

# --- 机器人行为 ---
bot.private_enabled=true
bot.wake_words=Haruka,haruka   # 正文里出现就叫醒她，不需要 @
bot.group_require_at=true      # 群里是否要求被叫到；false = 每条都回
bot.owner_qq=                  # 主人的 QQ 号，逗号分隔；留空 = 不设主人
bot.owner_title=主人           # 怎么称呼主人，同时也是消息前缀
bot.proactive_enabled=false    # 主动接话（像提问的群消息）
bot.proactive_cooldown_seconds=60
bot.proactive_max_per_hour=6
bot.workers=4                  # 改这个要重启

# --- 控制台 ---
web.enabled=true
web.host=127.0.0.1
web.port=18081
web.secret=                    # 留空 = 本机不校验
web.static_dir=webui

# --- 长期记忆 ---
memory.enabled=true
memory.db_path=data/haruka.db
memory.inject_limit=8          # 每次带几条记忆进提示
memory.extract_enabled=true
memory.min_importance=0.7      # 调高 = 她更挑（0.9 只记非常重要的）
memory.max_per_scope=300

# --- 画图 ---
image.enabled=true
image.model=gemini-3.1-flash-image   # 走 Gemini 的端点，用的是上面的 llm.base_url
image.save_dir=data/images
image.send_mode=file                 # 收不到图就改 base64

# --- 头像 ---
avatar.enabled=true
avatar.look_keywords=头像,长相,样子,照片,自拍
```

## 她会做什么

- **聊天**：私聊、群聊，按人设说话。上下文按会话隔离，各聊各的。
- **记住事**：自动挑长期有价值的信息存进 SQLite。**明确说「记住」时一定记**。控制台「记忆」页可增删改、导出导入。
- **画图**：她自己决定，画好后作为单独一条图片消息发出来（十几秒）。
- **看头像**：消息里提到「头像 / 长相 / 样子」时，她能真的看到发送者头像。
- **读合并转发**：**先转发那段聊天记录**（不用 @），**再 @ 她问** —— QQ 里没法在同一条消息里同时做这两件事。
- **认主人**：把主人的 QQ 填进 `bot.owner_qq` 之后，主人的消息会带 `【主人】` 前缀交给她，所以她知道该听谁的。没填的话她只知道消息内容、不知道是谁说的。

## 常见问题

- **控制台打不开**：确认 `web.enabled=true`，且 `web.port` 没被占用（`netstat -ano | findstr 18081`）。
- **控制台报密钥不正确**：`web.secret` 留空时，密钥框也要留空。
- **SnowLuma 连不上 / 日志报 `access_token 不匹配`**：两边的 token 不一致。用控制台「连接 QQ」卡片里显示的那一串。
- **群里 @ 了不回**：看日志有没有 `[收到]`。有 `[收到]` 没 `[回复]` 就是模型那边的问题。
- **模型报 401 / 404**：`llm.base_url` 和 `llm.provider` 不配套（见上面的表），或者 key 不对。
- **报「返回了空文本」**：把 `llm.max_tokens` 调大。
- **拉取模型列表失败**：按控制台里显示的「实际请求」地址核对 `base_url` 该填到哪一层。
- **画好了但 QQ 里收不到图**：`image.send_mode` 从 `file` 改成 `base64`。
- **记忆不增长**：确认 `memory.enabled` 和 `memory.extract_enabled` 都是 true；太短的消息不触发。
- **中文乱码**：启动时加 `-Dfile.encoding=UTF-8`（`run.bat` / `run.sh` 已经带了）。

## 备份与迁移

带这三个文件就够：

| 文件 | 装了什么 |
| --- | --- |
| `data/haruka.db` | 长期记忆 |
| `haruka.md` | 人设 |
| `config.properties` | API Key 与配置 |

`data/avatars` 和 `data/images` 是可丢弃的缓存，不用带。

## 凭据安全

仓库里**只有 `config.example.properties`（模板）**，你复制出来的 `config.properties` 永远不会被跟踪。

```gitignore
*.properties
!config.example.properties
```

`.githooks/pre-commit` 会拦住带凭据的提交。hooks 不会被 `git clone` 带走，所以**每个 clone 启用一次**：

```bat
git config core.hooksPath .githooks
```

## 许可证

MIT，见 [LICENSE](LICENSE)。
