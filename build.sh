#!/usr/bin/env sh
# haruka 构建脚本（Linux / macOS）
# 需要在 PATH 上有 JDK 17+ 和 Maven。

set -e
cd "$(dirname "$0")"

if ! command -v mvn >/dev/null 2>&1; then
  echo "[build] 找不到 mvn。请安装 Maven 并加进 PATH。" >&2
  exit 1
fi

mvn -q -DskipTests clean package
echo "[build] 完成：target/haruka.jar"
