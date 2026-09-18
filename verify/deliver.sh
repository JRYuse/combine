#!/usr/bin/env bash
# 交付模组：编译（**必须兼容安卓**）→ 检查调试残留 → 放到 ~/sd
#
#   verify/deliver.sh
#   OUT_DIR=/别的目录 verify/deliver.sh
#
# 为什么必须用 deploy：`./gradlew jar` 只出 combineDesktop.jar（纯桌面）；
# `./gradlew deploy` = desktop + android 合并，产物里有 classes.dex，安卓端才能装。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$HOME/sd}"
JAR="$ROOT/build/libs/combine.jar"

cd "$ROOT"

echo "[deliver] 1/3 编译（兼容安卓：./gradlew --offline deploy）"
./gradlew --offline deploy

[ -f "$JAR" ] || { echo "没编出 $JAR" >&2; exit 1; }
if ! unzip -l "$JAR" | grep -q "classes.dex"; then
  echo "产物里没有 classes.dex —— 这不是安卓可用的包（是不是只跑了 jar？）" >&2
  exit 1
fi

echo "[deliver] 2/3 检查调试残留"
LEFTOVER="$(grep -rnE '\[dbg\]|dbgTicks|System\.out\.print|Log\.info\("\[drv\]' "$ROOT/src" 2>/dev/null || true)"
if [ -n "$LEFTOVER" ]; then
  echo "还有调试代码，先删掉再交付：" >&2
  echo "$LEFTOVER" >&2
  exit 1
fi

echo "[deliver] 3/3 放到 $OUT_DIR"
mkdir -p "$OUT_DIR"
cp "$JAR" "$OUT_DIR/combine.jar"
# 只放 combine.jar —— 带版本号的文件名由使用者自己改，脚本不生成
ls -l "$OUT_DIR/combine.jar"
echo "[deliver] 好了：$OUT_DIR/combine.jar（安卓+iOS 都能装；要改名/带版本号自己来）"
