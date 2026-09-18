#!/usr/bin/env bash
# 造一个"游戏数据目录"（= mods/ + settings 等），给 headless 测试和真客户端用。
#
#   verify/make-dataset.sh <目标目录> [要装的模组路径或目录...]
#
# 固定会装：本仓库的 combine.jar（跑前请先 ./gradlew --offline deploy）
#
# 例子：
#   verify/make-dataset.sh /tmp/mp_coop/data "/tmp/mods/wasteland"        # 废土科技
#   verify/make-dataset.sh /tmp/mp_sf/data   /tmp/mods/satfire.jar        # 饱和火力
#   verify/make-dataset.sh /tmp/mp_cj/data   /tmp/mods/cooptestjava.jar   # java 扩展测试模组
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${COMBINE_JAR:-$ROOT/build/libs/combine.jar}"

if [ $# -lt 1 ]; then
  echo "用法: $0 <目标目录> [模组路径...]" >&2
  exit 2
fi
target="$1"; shift

[ -f "$JAR" ] || { echo "先 ./gradlew --offline deploy（没找到 $JAR）" >&2; exit 2; }

mkdir -p "$target/mods"
cp "$JAR" "$target/mods/combine.jar"
for m in "$@"; do
  if [ -d "$m" ]; then
    cp -r "$m" "$target/mods/"
  elif [ -f "$m" ]; then
    cp "$m" "$target/mods/"
  else
    echo "[warn] 跳过不存在的模组: $m" >&2
  fi
done
echo "[verify] 数据目录好了：$target"
ls -l "$target/mods"
