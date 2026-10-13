# haruka

一个**极简的 Java QQ 聊天机器人**：只做一件事 —— 让 QQ 有人跟你聊。单 jar，零前端构建步骤，除 4 个运行库外没有任何依赖。

功能范围参考了 [SuInk/Diana](https://github.com/SuInk/Diana) 的设计思路（那是一个 Go 写的、功能齐全得多的项目）。**这里是独立的 Java 实现，不含 Diana 的任何代码** —— 两者的依赖栈、架构和代码完全不同，许可证也各自独立（见文末「许可证与致谢」）。

砍掉了扩展、群管理、接话评分、发送前审核、持久化队列、联网/无头浏览器、插件，只保留核心链路：

```
                        haruka.md（人设文件，改一下保存即生效）
                              │
  SnowLuma ──反向WS──> OneBotServer ──> 触发判定 ──> 补齐素材 ──> LLM ──> 发回原会话
  (OneBot v11)              │                        │         │
                            │       合并转发的正文 ────┘         │
                            │       QQ 头像图片 ───────┘         │
                            │                                   │
                            └──> 长期记忆(SQLite) <──自动抽取─────┘
                                          │
                                          └──> 画图（她自己点单，可带头像当参考图）──> 图片消息
```

**控制台是和主体解耦的。** 核心只暴露一组带密钥的 HTTP API，前端是独立的静态页面 —— 和 mihomo 的 webui 一个模式。所以：

- 可以只跑机器人，一个 HTTP 端口都不开（`web.enabled=false`）；
- 可以只跑控制接口、不连 QQ（`--web-only`）；
- 前端可以放在任何地方（本地文件、静态托管），在后端地址里填 `http://127.0.0.1:18081` 连上去；
- **配置始终以 `config.properties` 为准**，手改文件同样生效，控制台只是个可选的便利层。

## 目录

| 文件 | 作用 |
| --- | --- |
| `OneBotServer.java` | 反向 WebSocket 服务端：鉴权、解析消息事件、把回复发回去 |
| `OneBotApi.java` | 往 OneBot 发 API 请求（`send_private_msg` 等），用 `echo` 匹配响应 |
| `ChatService.java` | 每个会话（私聊 / 每个群）各自的历史，串行处理 |
| `GeminiLlmClient.java` / `OpenAiLlmClient.java` | 两个协议各一个实现 |
| `LlmRouter.java` | 按配置选实现，支持热切换协议 |
| `MemoryStore.java` | 长期记忆的 SQLite 单文件存储（刻意不开 WAL，见下） |
| `MemoryExtractor.java` | 每轮对话后异步抽取值得长期记住的事 |
| `MemoryPrompt.java` | 把长期记忆拼成系统提示（真实对话和控制台自测共用） |
| `Persona.java` | 人设加载：支持独立的 md/txt 文件，改文件即生效 |
| `ImageClient.java` | 画图：走 Gemini 生图模型，跳过思考草稿取成品图；支持参考图（图生图） |
| `AvatarFetcher.java` | 抓 QQ 头像：qlogo 固定地址，不需要接口调用 |
| `persona.example.md` | **示例人设**：复制一份改成你自己的角色即可 |
| `Config.java` / `ConfigStore.java` / `ConfigWatcher.java` | 配置读取、写回、以及**文件被手改后自动热重载** |
| `WebConsole.java` | **只有 HTTP API**，不提供页面也不依赖页面 |
| `Log.java` / `RuntimeStatus.java` | 自带日志（含环形缓冲）+ 运行状态 |
| `webui/index.html` | **独立的前端页面**，单文件、零依赖，可 `file://` 直接打开 |
| `config.example.properties` | **配置模板**（全是 `CHANGE_ME` / 空值，可安全提交） |
| `LICENSE` | MIT |

## 前置

- **JDK 17+**（`build.bat` 读 `JAVA_HOME`，读不到就试几个常见安装位置）
- **Maven**（`build.bat` 优先用 PATH 上的 `mvn`，找不到就找 IntelliJ 自带的那个）
- 一个 OneBot v11 实现（本文以 **SnowLuma** 为例；其它实现只要有反向 WebSocket 也一样）

## 三步跑起来

### 1. 准备配置

```bat
copy config.example.properties config.properties
```
```sh
cp config.example.properties config.properties
```

**为什么要复制一份**：`server.access_token` 首次启动会自动生成并**写回配置文件**，而 `llm.api_key` 也在里面。所以模板必须是独立的 `config.example.properties`，你自己那份 `config.properties` 则被 `.gitignore` 挡着 —— 否则一个 `git add` 就会把凭据推进仓库。

必须改的只有一项：

- `llm.api_key` → 你的大模型 API Key

**`server.access_token` 留空就行** —— 首次启动会自动生成一串随机凭据，写回配置文件，并在启动日志里用醒目的块打印出来：

```
================== 首次启动：已生成连接凭据 ==================
  server.access_token = <一串 32 位随机凭据>
  把它原样填进 SnowLuma「反向 WebSocket」的 Access Token。
  已写回配置文件，以后不用再管：config.properties
==============================================================
```

把那串抄进 SnowLuma 即可，**不需要你自己想一个**。第二次启动不会重新生成。

> 为什么不让用户猜、也不给个默认值？因为 `server.host` 默认是 `0.0.0.0`。一个写在开源仓库里的默认口令等于没有口令 —— 谁都能伪装成 SnowLuma 连上你的 18080。所以模板里留空，启动时现生成。

按需改：`llm.provider`（`openai` 或 `gemini`）、`llm.base_url`、`llm.model`、`llm.system_prompt_file`（人设文件，见下）。

**两个协议对 `base_url` 的要求不一样**：

| `llm.provider` | 实际请求 | `base_url` 该写到哪一层 |
| --- | --- | --- |
| `openai` | `POST {base_url}/chat/completions` | 写到 `/v1`，例：`https://api.openai.com/v1` |
| `gemini` | `POST {base_url}/v1beta/models/{model}:generateContent` | 写主机加端口，或直接贴 `/v1beta/models`，两种都行 |

### 2. 构建

```bat
build.bat
```
```sh
./build.sh
```

成功后产物是 `target/haruka.jar`。

### 3. 启动

```bat
run.bat
```
```sh
./run.sh
```

| 跑法 | 命令 |
| --- | --- |
| 机器人 + 控制接口 | `run.bat` / `./run.sh` |
| **纯机器人**（不开任何 HTTP 端口） | 配置里 `web.enabled=false` |
| **只跑控制接口**（不连 QQ、不占 18080） | `run.bat config.properties --web-only`（Linux 同理，只是多个参数） |
| 指定配置文件 | `run.bat my.properties` / `./run.sh my.properties` |
| 打开调试日志 | 追加 `--debug` |

两个脚本都**先找 `target/haruka.jar`、再找同级的 `haruka.jar`** —— 所以源码布局和发布包布局（jar 和脚本平铺在一起）都能直接用。

### 跑起来之后

浏览器打开 **`http://127.0.0.1:18081`** 就是控制台。

前端**不需要单独部署** —— 页面已经打进 jar 了。后端会先看 `web.static_dir` 指的磁盘目录，目录不存在就自动改用 jar 内置的那一份。所以哪怕只有一个裸 jar，控制台也是可用的；反过来，你把 `webui/` 放在工作目录里就能不重新打包地替换界面。

> 这也意味着**不需要为不同平台分别编译**：Java 字节码与平台无关，而 SQLite 的原生库（Windows / Linux / **Alpine(musl)** / macOS / FreeBSD，含 x86_64、aarch64、armv7、riscv64）全都在同一个 jar 里。同一个 jar 直接就能在 Ubuntu 和 Windows 上跑，区别只有启动脚本用 `.sh` 还是 `.bat`。
>
> 唯一需要分平台的场景是**想连 JRE 一起打包**（让用户不必自己装 Java 17），那才需要 `jpackage` / `jlink` 各平台各打一份。

## 配置怎么改

**两条路都行，效果完全一样。**

**路一：直接编辑 `config.properties`。** 保存后**不用重启** —— 程序每 2 秒检查一次文件，发现变化就热重载。改坏了也不怕：解析失败会保留原配置并在日志里说明，不会把运行中的机器人搞挂。

> 例外：`server.host`、`server.port`、`bot.workers`、`web.host`、`web.port`、`web.enabled` 这六项必须重启。其余（LLM 全部参数、触发规则、密钥、静态目录）都是热生效。

**路二：用控制台。** 它只是帮你改同一个文件，注释和键顺序都会保留。

## 长期记忆

只存**关键记忆** —— 关于某人/某群稳定的事实，不存聊天历史。所以重启之后她记得"你喜欢冰美式"，但不记得上一句说了什么。

**记什么由她自己判断，而且默认答案是「不记」。** 抽取提示词明确写着：一天聊一百句通常一条都不该记；宁可漏记，也不要记一堆没用的。让她记的判据是两条同时成立 —— 「过了半年仍然有用」且「对方会期待你记得」。

她还必须给自己写的每条候选打一个 **importance（0–1）**，低于 `memory.min_importance`（默认 0.7）的一律不写进库。所以有两道闸：一道是她自己的判断，一道是你能调的门槛。

**例外（很重要）**：对方明确说了「记住 / 别忘了 / 记一下」时，**必须落一条**，而且绕开重要性门槛；万一模型没给出候选，就把那句话本身存下来。因为她经常顺口回「已经记好啦」—— 那句话必须是真的，不能嘴上答应而库里什么都没有。

- **自动抽取**：每轮回复成功后，让她判断这轮有没有值得长期记住的事。一轮最多记 `2` 条。按内容指纹去重（同一件事说十遍也只有一条）。太短的消息不触发。
- **自动注入**：回复前按会话取出相关记忆（本会话的 + 全局的，按命中次数和最近使用排序），拼在人设之后。注入条数由 `memory.inject_limit` 控制。
- **手动维护**：控制台里有记忆面板，可以搜索、新增、修改、删除、导出、导入。
- **不用 QQ 也能验证**：控制台的「模型自测」填上会话 scope 后，会走完整链路（注入 → 回复 → 可选抽取），并显示这次实际注入了哪几条、新落库几条。排查"她怎么不记得"时很有用。
- **开关也在控制台里**：`memory.enabled`、`memory.extract_enabled`、注入条数、抽取门槛、**重要性门槛**、每会话上限都能改（`enabled` 和 `db_path` 改动需要重启）。

觉得她记太多就调高 `memory.min_importance`（0.9 基本只记"非常重要"的），或者干脆 `memory.extract_enabled=false` 只靠手动维护。

### 怎么把「她」搬到另一台机器

**只有记忆需要带走。** 三个文件：

| 文件 | 装了什么 | 不带会怎样 |
| --- | --- | --- |
| `data/haruka.db` | 她记住的事（唯一不可再生的东西） | 她变成陌生人 |
| `haruka.md` | 她是谁（人设） | 换个人格 |
| `config.properties` | 怎么找到她（API Key 与规则） | 起不来 |

把这三个放进新机器的 haruka 目录，启动就是同一个她。

**头像缓存（`data/avatars`）和画过的图（`data/images`）不用带** —— 那是可丢弃的缓存，到新机器会自己重建；画过的图要不要留是另一回事，跟"她是谁"无关。

几个刻意为之的细节，保证「拷一个文件就够」：

- **单文件模式**：数据库强制 `journal_mode=DELETE`，**不产生 `.db-wal` / `.db-shm` 边车文件**。SQLite 默认的 WAL 模式会让「拷数据库」变成「记得拷三个文件，漏一个就丢最近的数据」—— 对这个项目来说这个代价不值得。
- **稳定标识**：记忆按 `group:<群号>` / `private:<QQ号>` 归档，都是 QQ 号，换机器不变。不用任何机器本地 ID。
- **退出时落盘**：正常停止会把改动合并进主文件。

实在不想带 `.db`，控制台里的**导出/导入 JSON** 也能把记忆搬走（导出的就是"她记得的事"本身）。

不想用这个功能：`memory.enabled=false` —— 完全不碰数据库，行为和加这个功能之前一样。

> 注：SQLite 驱动会让 jar 从约 2.5MB 涨到约 15MB（里面是各平台的本地库）。

## 人设

人设放在一个独立的 markdown 文件里，比塞进 `.properties` 的单行值舒服太多：能写多段、能排版、能用 git 管版本。

**仓库里不带人设** —— `haruka.md` 在 `.gitignore` 里，因为那是你自己的角色设定。先复制示例：

```bat
copy persona.example.md haruka.md
```

然后让程序指向它：

```properties
llm.system_prompt_file=haruka.md   # 人设主体
llm.system_prompt=                 # 内联补充指令，会追加在文件内容后面
```

留空也行 —— 那就只用 `llm.system_prompt` 那一行内联文本，简单场景够用。

> 写人设时最值钱的部分是**"她不该做什么"**：模型天然倾向于讨好和编造，把硬线写清楚比堆形容词有效得多。`persona.example.md` 里给了一份能直接用的起点。

- **改文件即生效**：`ConfigWatcher` 每 2 秒检查一次，保存后人设立刻换掉，不用重启。
- **控制台里也能编辑**：「人设」卡片是一个大文本框，保存即生效。
- **冲突策略以控制台为准**：如果文件在别处被改过而控制台页面还停在旧内容，保存**不会**被拒绝，但会先把旧版备份成 `haruka.md.bak-20261013-113058-5ee1` 再写入。把有内容的人设存成空白时同样会先备份 —— 误操作能救回来。
- 控制台保存过之后，`haruka.md.bak-*` 文件可以随手删掉。

## 画图

haruka 可以自己决定要不要画图：

- 代码在系统提示里追加一段能力说明（**不用你写进人设文件**），告诉她可以用 `[[draw: 英文画面描述]]` 点单。
- 她发出标记后，后端把标记**从回复文本里摘掉**、调用生图模型、把图片作为一条独立消息发出去。
- 生成一张要**十几秒**，所以是「先收到文字、过一会儿收到图」。
- 原图都留在 `image.save_dir`（默认 `data/images`）里。

配置：

```properties
image.enabled=true
image.model=gemini-3.1-flash-image   # Gemini 系生图模型
image.save_dir=data/images
image.style=                          # 追加到每张图提示词后的风格描述
image.send_mode=file                  # file=发本地路径（消息小）；base64=兼容性更好
image.timeout_seconds=180
image.max_per_turn=2                  # 一轮最多画几张
```

> 实测契约（不是照文档猜的）：请求体只要 `contents`，**不要**加 `responseModalities`；
> 返回的 `parts` 里混着若干 `thought:true` 的思考草稿（有些也带图），要取的是**最后一个
> 不带 thought 标记**的 `inlineData`。`image.send_mode=file` 用的是 `Path.toUri()`，
> 中文路径会被正确百分号编码。如果 SnowLuma 那边收不到图，改成 `base64` 再试。
>
> ⚠️ **画图走的是 Gemini 的 `generateContent` 端点，用的是 `llm.base_url`。** 所以
> `llm.provider=openai` 时，除非你那个 `base_url` 同时也提供 Gemini 端点，画图会失败 ——
> 想用画图，就配一个 Gemini 兼容的 `llm.base_url`。

## 头像

OneBot v11 的消息事件里**只有文字、没有头像图片** —— 这是标准如此，不是本项目的缺陷。所以想让她"看得见你的头像"，得自己动手。好在 QQ 头像地址是**纯拼接的固定地址，不需要任何接口调用**：

```
https://q1.qlogo.cn/g?b=qq&nk=<QQ号>&s=<尺寸>      # 个人
https://p.qlogo.cn/gh/<群号>/<群号>/<尺寸>          # 群
```

消息里命中 `avatar.look_keywords`（默认 `头像,长相,样子,照片,自拍`）时，后端把发送者头像**下载下来作为图片附件**跟这条消息一起发给多模态模型 —— 她这才算真的看得见。

- 头像是**可选的**：抓不到就当她这次没看见，**绝不影响回复**。
- 有缓存（`avatar.cache_minutes`，默认 30 分钟），同一个人反复触发不会重复下载。
- 关掉：`avatar.enabled=false`。
- 用别的模型时注意要**多模态**的，纯文本模型收到图片附件会报错或忽略。

**画图时也能用**：她可以在标记里点名参考图 —— `[[draw: 描述 | avatar]]` 会把发送者头像当参考图一起给生图模型（"根据我的头像画"就走这条），`[[draw: 描述 | group]]` 用本群群头像。不点名就是纯文字生图。

> 她**能看头像，但看不了别人发来的图片** —— 对方发的图在消息里只是 `[图片]` 占位，不解析内容。

## 合并转发（聊天记录）

QQ 的合并转发在消息事件里**只有一个 id**（`[CQ:forward,id=...]`），正文要单独调 `get_forward_msg` 取回来。

**用法**：QQ 里没法在同一条消息里既 @ 人又转发记录，所以流程是「**先转发那段记录**（不用 @），**再 @ 她问**」。转发来的那一段会先按会话存着（5 分钟内有效），下一句话自动用上。

```
[合并转发] 已为 group:… 存下 1 段聊天记录（158 字），等她下一句话
[合并转发] 用上了之前存下的一段聊天记录（158 字）
```

- 各家 OneBot 实现送的字段不完全一样，所以解析做成容错的。认不出形状时会打 `[合并转发] 检测到转发迹象但没解析出内容，原始消息: {…}`，把原始报文带出来，照着改解析即可。
- 内容超过 3000 字会截断 —— 免得一段超长记录把上下文撑爆。
- 取不到时她会**明确说看不到**，而不是瞎猜。留个 `[合并转发]` 占位符的话，模型会把"看不到内容"当成内部推理直接吐出来（实测踩过这个坑）。
- 取件**必须放在聊天线程上做**：在读线程里同步等这个响应会死锁（响应本身要靠读线程处理）。这是本项目里最容易踩的坑。

## 控制台怎么用

前端是 `webui/index.html`，一个独立的单文件页面。三种打开方式：

1. **让后端一起提供**：默认 `web.static_dir=webui`，浏览器打开 `http://127.0.0.1:18081` 就是它。
2. **直接用浏览器打开文件**：双击 `webui\index.html`（`file://`），在后端地址里填 `http://127.0.0.1:18081`。
3. **托管在任何地方**：把这一个文件丢到任意静态服务器上。

打开后先填**后端地址**和**密钥**（`web.secret`，没设就留空），然后就能：

| 区块 | 内容 |
| --- | --- |
| 运行状态 | SnowLuma 连接状态、QQ 号/昵称、运行时长、收到/已回复/失败计数、当前协议与模型；是否处于"仅控制台"模式、上次从磁盘重载配置的时间 |
| LLM 配置 | provider、base_url、api_key（掩码，留空=不改）、model（**带"拉取模型列表"按钮**）、system_prompt、temperature、max_tokens 等 |
| 人设 | 人设文件路径、**大文本框直接编辑人设**、保存即生效、外部改动会先自动备份 |
| 机器人行为 | 私聊开关、群聊需 @、**唤醒词**、主动接话（开关 / 只看提问 / 冷却 / 每小时上限）、并发数 |
| 画图 | 开关、生图模型、保存目录、风格描述、发送方式、超时、每轮上限 |
| 头像 | 开关、命中关键词、尺寸、缓存时长与目录、下载超时 |
| 长期记忆 | 记忆总数与数据库路径、按会话筛选、搜索、新增/编辑/删除、导出/导入 JSON、重要性门槛 |
| 实时日志 | SSE 流，`[收到]` / `[回复]` / 失败一目了然，可按级别过滤、暂停、自动滚动；群消息还会标出**触发原因** |
| 其他 | 「模型自测」（不经过 QQ 直接试一次：填 scope 走记忆链路、填发送者 QQ 号验证看头像、勾上画图会真的出图）、「重新加载配置」、「保存配置」 |

**安全**：控制接口能读到你的 API Key、也能改写配置。所以

- 默认只监听 `127.0.0.1`；
- 想把 `web.host` 改成非本机地址，**必须先设 `web.secret`**，否则程序拒绝启动；
- 密钥走 `Authorization: Bearer`，不依赖 cookie，因此跨源页面（含 `file://`）也能用。

## SnowLuma 怎么配

1. 查本机局域网 IP：`ipconfig`，找 `IPv4 地址`（例如 `192.168.1.5`）。SnowLuma 和本程序在**同一台机器**上就写 `127.0.0.1`。
2. 在 SnowLuma 里加一个 **反向 WebSocket** 连接：
   - 地址：`ws://192.168.1.5:18080/onebot/v11/ws`
   - Access Token：填**启动日志里打印的那一串**（也就是 `config.properties` 里 `server.access_token` 的值）；如果你是全新配置，先启动一次 haruka，它会自己生成并打印出来
3. SnowLuma 连上后，日志会打印：

```
接入端已连接: path=/onebot/v11/ws self_id=你的QQ号 来自 /192.168.1.5:xxxxx
登录信息: {"user_id":你的QQ号,"nickname":"xxx"}
```

4. 然后用另一个号私聊机器人，或者群里 @ 它。

路径其实不校验，你 SnowLuma 里习惯用什么路径都行；程序只看 Access Token。

## 触发规则

群聊里她会在下面任一条件成立时开口：

1. **被 @**（永远有效）
2. **正文命中唤醒词**（`bot.wake_words`，例如 `Haruka,haruka,小春`）—— **不需要 @**，也不受 `group_require_at` 影响
3. **主动接话**：`bot.proactive_enabled=true` 且这条消息"看起来像提问"，且限流允许
4. `bot.group_require_at=false` → 群里每条消息都回（只适合很小的群）

私聊收到就回（`bot.private_enabled=true`）。机器人自己发的消息不会触发回复（防死循环）。

> ⚠️ 因为那条防死循环，**用机器人自己的号给自己发消息是不会回的**。测试必须用另一个 QQ 号。

触发原因会打在日志里，方便调：

```
[收到] group:123456789 来自 阿喵: 有人知道这个怎么弄吗？   ← 主动接话（像提问）
[收到] group:123456789 来自 阿喵: Haruka 在吗             ← 命中唤醒词
```

### 主动接话是"便宜版"

它靠**规则 + 限流**判断，**不额外调模型**，所以不如评分模型准 —— 该接的没接、不该接的接了都可能发生。判定规则是「正文结尾是 `?`/`？`，或者以 吗/呢/怎么/为什么/什么/哪里/谁/有没有/能不能 等疑问词收尾」。

限流由 `bot.proactive_cooldown_seconds`（同一个群两次开口的间隔）和 `bot.proactive_max_per_hour`（每群每小时上限）控制。

Diana 的做法是每条没被点名的群消息都额外调一次**接话评分模型**（六档门槛），准得多，但成本也高得多。真要准就得付出那个代价。

## 常见问题

- **SnowLuma 连不上**：Windows 防火墙第一次会弹窗，要允许；或者端口被占用（`netstat -ano | findstr 18080`）。
- **启动日志说控制接口启动失败、提示 web.secret 为空**：你把 `web.host` 改成了非本机地址但没设密钥。补上 `web.secret` 即可。
- **控制台页面打不开**：如果 `web.static_dir` 指向的目录不存在，后端就只提供 API —— 直接用浏览器打开 `webui\index.html`，在后端地址里填 `http://127.0.0.1:18081`。
- **控制台报"密钥不正确"**：`web.secret` 和页面上填的不一致。
- **手改了配置文件但没生效**：等 2 秒（自动重载），或在控制台点"重新加载配置"。若改的是那六个需要重启的项，就重启。
- **日志报 `拒绝握手: access_token 不匹配`**：两边 token 不一致，重新核对。
- **群里 @ 了不回**：确认 `bot.private_enabled` / `group_require_at`，以及日志里有没有 `[收到]`。
- **报 LLM 400/401**：检查 `llm.base_url` 是不是切合当前 provider 的层级要求（见上面的表），以及 key 对不对。
- **报 `Gemini 返回了空文本 (finishReason=MAX_TOKENS)`**：Gemini 系的思考 token 也占 `max_tokens`，把它调大（默认 2048）。
- **回复乱码**：`run.bat` 已经设了 UTF-8，如果自己用命令启动，加上 `-Dfile.encoding=UTF-8`。
- **记忆不增长**：确认 `memory.enabled` 和 `memory.extract_enabled` 都是 true；消息太短（少于 `memory.extract_min_chars`）不会触发抽取；看日志里有没有 `记住了 N 条新信息`。关掉自动抽取时，也可以只靠控制台手动加。
- **日志报 `打开记忆库失败，记忆功能本次不可用`**：目录没写权限或 `memory.db_path` 指到了不存在且建不了的路径。机器人其余功能不受影响。
- **换机器后记忆没跟过来**：确认拷的是 `data/haruka.db`（以及 `.db-journal`，如果进程没正常退出的话），并且新机器的 `memory.db_path` 指向同一个相对位置。
- **她说会画图但没画**：只有模型主动发出 `[[draw: ...]]` 标记才会画。如果她只是嘴上答应，说明这句指令对当前模型不够硬 —— 可以换 thinking 档更高的模型，或把 `image.model` 调开。日志里出现 `画好一张图` 才算真的在画。
- **画好了但 QQ 里收不到图**：把 `image.send_mode` 从 `file` 改成 `base64` 再试。另外 `data/images` 里的原图本身就是证据 —— 有文件就说明生成成功，问题在发送那一步。
- **画图很慢**：正常，一次十几秒。所以设计成"先回文字、图随后到"，不是卡住了。一轮最多画 `image.max_per_turn` 张。
- **改了 haruka.md 没生效**：等 2 秒；确认 `llm.system_prompt_file` 指向的路径对（控制台「人设」卡片顶部会显示解析后的绝对路径）。如果日志里有 `人设文件 ... 变化，已重新加载` 就说明接住了。
- **控制台保存人设后多出 `haruka.md.bak-*`**：说明保存时磁盘上的版本和你页面里的不一致（或你把它存成了空白），后端留了备份。确认内容没问题后删掉即可。
- **她读不到合并转发的聊天记录**：现在能读了。合并转发的正文**不在消息事件里**（只有一个 `[CQ:forward,id=...]`），得单独调 `get_forward_msg` 取回来。
  - **用法**：QQ 里没法在同一条消息里既 @ 人又转发记录，所以流程是「**先转发那段记录**（不用 @），**再 @ 她问**」。转发过来的那一段会先按会话存下来（5 分钟内有效），下一句话自动用上。
  - **日志**：`[合并转发] 已为 group:… 存下 1 段聊天记录（158 字），等她下一句话` → `[合并转发] 用上了之前存下的一段聊天记录（158 字）`。
  - **认不出形状时**：会打 `[合并转发] 检测到转发迹象但没解析出内容，原始消息: {…}`，把 SnowLuma 送的原始报文带出来，照着改解析即可（各家中转的 type 名和字段不完全一样，所以这段要做成容错的）。
  - 取件**必须放在聊天线程上做**：在读线程里同步等这个响应会死锁（响应本身要靠读线程处理）。
  - 取不到内容时她会**明确说看不到**，而不是留个占位符瞎猜 —— 早先正是因为只喂了 `[合并转发]` 占位符，她把"看不到内容"当成内部推理直接用英文吐了出来。

## 控制接口一览

需要密钥时都带 `Authorization: Bearer <web.secret>`。

| 接口 | 用途 |
| --- | --- |
| `GET /api/meta` | 名称、版本、配置文件路径、静态目录、是否仅控制台模式 |
| `GET /api/status` | 运行状态 |
| `GET /api/config` | 读取配置（API Key 与密钥永不下发，只给"是否已设置"） |
| `POST /api/config` | 保存配置（只改动的行） |
| `POST /api/reload` | 从磁盘重新加载配置 |
| `GET /api/config/raw` | 配置文件全文 |
| `GET /api/models` | 按当前 provider 拉取模型列表 |
| `POST /api/test-llm` | 试一次模型；带 `scope` 走「记忆注入 → 回复 →（可选）抽取」，带 `image` 还会真的把图画出来 |
| `GET /api/persona` | 读人设文件（含路径、字数、mtime） |
| `POST /api/persona` | 写人设文件；文件被外部改过或存成空白时会先自动备份，响应里的 `backup` 是备份路径 |
| `GET /api/logs/history?after=N` | 历史日志 |
| `GET /api/logs/stream?after=N&secret=…` | SSE 实时日志（`EventSource` 不能带请求头，所以这条额外收 `secret` 查询参数） |
| `GET /api/memories` | 列出记忆（可按 `scope` / `q` / `limit` 过滤），附带 scope 列表与总数 |
| `POST /api/memories` | 新建（`id=0`）或修改（`id>0`）一条记忆 |
| `POST /api/memories/delete` | 删除一条记忆 |
| `GET /api/memories/export` | 导出全部记忆为 JSON |
| `POST /api/memories/import` | 导入（`mode` 为 `merge` 或 `replace`） |

## 相比 Diana 砍掉了什么

- 没有持久化入站队列 —— **会话历史只在内存里，重启就没了**（长期记忆是另一回事，它在 SQLite 里，重启还在。所以她会一边"记得你在学 C 语言"，一边"不记得刚才那几句"，两句话都是真的）。消息处理失败也不重试。
- **主动接话是"便宜版"**：靠规则 + 限流，不额外调模型，所以不如评分模型准。没有发送前审核，也没有屏蔽名单。
- 没有工具 / Agent 循环、没有联网、没有无头浏览器、没有向量检索（记忆靠命中次数和最近使用排序，不是语义检索）。
- **看不了对方发来的图片**：只留 `[图片]` 占位，不解析内容。（但能看 QQ 头像、能读合并转发、能画图 —— 见前面几节。）
- 没有多平台（只有 OneBot v11 / QQ），没有插件，没有群管理。
- 没有 Docker、没有自更新、没有 CI —— 就是 `build.bat` + `run.bat`。

后面想加，建议按 Diana 的顺序补：**先持久化队列 → 再触发门禁 → 再接话评分 → 最后才是工具。**

## 凭据安全

这个项目里有三样东西**绝对不能进版本库**：`llm.api_key`、`server.access_token`、`web.secret`。它们都在配置文件里，所以规则很简单：

> **仓库里只有 `config.example.properties`（模板）。你自己那份 `config.properties` 永远不会被跟踪。**

`.gitignore` 的做法是**默认挡住所有 `*.properties`，只放行模板**：

```gitignore
*.properties
!config.example.properties
```

反过来写规则是有原因的：程序会在**首次启动时把生成的 `server.access_token` 写回**你正在用的那个配置文件，而你还可能给它起任何名字。逐个列举文件名一定会漏 —— 这个仓库就真的这么漏过一次（好在只进了未推送的提交，推送前清掉了）。

### 提交前守卫

`.githooks/pre-commit` 会拦住任何带凭据的提交。但 hooks 不会被 `git clone` 带走，所以**每个 clone 要各启用一次**：

```bat
git config core.hooksPath .githooks
```

它会拒绝这些内容：

- 非 `config.example.properties` 的 `.properties` 文件（它们会被写入真实凭据）
- 有值的 `server.access_token` / `llm.api_key` / `web.secret`
- 形如 `sk-…` 的 API Key
- Windows 用户目录的绝对路径（`C:\Users\你的名字\…`，会泄漏本机用户名）

确认是误报可以 `git commit --no-verify` 跳过。

### 如果不小心把凭据提交了

**只要还没推送就还有救**：`git reset --soft origin/main` 之后重新提交，凭据就不会进入待推送历史（本项目就是这么修的一次）。

**一旦推上去了，顺序不能反**：先**换掉那把凭据**（公开过的密钥一律视为已失窃），再考虑改写历史。只改历史而不换密钥等于没修。

## 许可证与致谢

本项目以 **MIT** 许可证发布，全文见 [LICENSE](LICENSE)。

设计思路受了 [SuInk/Diana](https://github.com/SuInk/Diana) 的启发 —— 那是一个功能完备得多的 Go 项目，本项目的**触发判定、长期记忆、主动接话、接入方式**等设计都参考了它的取舍。

但请注意两点：

1. **这里不含 Diana 的任何代码**，它是独立的 Java 实现（Diana 是 Go）。Diana 采用其自有的 Limited Redistribution License，与本项目无关，本项目也不对其代码做任何主张。
2. 如果你想要的是一个功能齐全的机器人（插件、群管理、多平台、发送前审核等），**请直接去用 Diana**，那才是它擅长的场景；这个项目刻意只做最小的一份。

