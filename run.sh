#!/usr/bin/env sh
# haruka 启动脚本（Linux / macOS）
#
# 用法:
#   ./run.sh                          # 用当前目录的 config.properties
#   ./run.sh config.local.properties  # 指定配置文件
#   ./run.sh --debug                  # 打开调试日志
#
# 需要在 PATH 上有 JDK 17+，或者设置 JAVA_HOME。

set -e
cd "$(dirname "$0")"

# --- 找 java：JAVA_HOME 优先，否则用 PATH 上的 ---
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=java
fi
if ! command -v "$JAVA" >/dev/null 2>&1; then
  echo "[run] 找不到 java。请安装 JDK 17+，或用 JAVA_HOME 指向它。" >&2
  exit 1
fi

# --- 找 jar：开发布局在 target/，发布布局和本脚本同级 ---
JAR=""
for candidate in target/haruka.jar haruka.jar; do
  if [ -f "$candidate" ]; then
    JAR="$candidate"
    break
  fi
done
if [ -z "$JAR" ]; then
  echo "[run] 找不到 haruka.jar。" >&2
  echo "[run] 开发布局请先跑 ./build.sh；发布包请把 haruka.jar 和本脚本放在同一目录。" >&2
  exit 1
fi

exec "$JAVA" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
  -jar "$JAR" "$@"
