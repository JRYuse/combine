#!/usr/bin/env bash
# 跑一套 headless 逻辑测试（不需要图形界面，几秒出结果）。
#
#   verify/run-headless.sh <game> <数据目录> <测试类> [额外的 java -D 参数...]
#     game = mx        → MindustryX（/root/sd/x.jar；它自己没带 headless 后端，自动补官方 160.1 的）
#     game = vanilla   → 官方/自编原版（~/Mindustry/desktop/build/libs/Mindustry.jar）
#     game = <某个 jar 路径>
#
# 例子：
#   verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.DetachTest     # 废土科技
#   verify/run-headless.sh mx /tmp/mp_cj/data   combine.dbg.DetachTest     # java 扩展测试模组
#   verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.FilterTest
#   verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.ContentTableTest -Dseed=container
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HERE="$ROOT/verify"
SERVER_JAR="${SERVER_JAR:-/tmp/server160.jar}"     # 官方 160.1 服务端 jar（提供 arc headless 后端）
MINDUSTRY_JAR="${MINDUSTRY_JAR:-$HOME/Mindustry/desktop/build/libs/Mindustry.jar}"
MINDX_JAR="${MINDX_JAR:-/root/sd/x.jar}"

if [ $# -lt 3 ]; then
  echo "用法: $0 <mx|vanilla|jar路径> <数据目录> <测试类> [java -D 参数...]" >&2
  exit 2
fi

game="$1"; data="$2"; test="$3"; shift 3

case "$game" in
  mx)      GAME_JAR="$MINDX_JAR";   EXTRA_CP="$SERVER_JAR" ;;   # 类顺序：x.jar 在前 → 游戏类都来自 MindustryX
  vanilla) GAME_JAR="$MINDUSTRY_JAR"; EXTRA_CP="" ;;
  *)       GAME_JAR="$game"; EXTRA_CP="" ;;
esac

if [ ! -f "$GAME_JAR" ]; then echo "找不到游戏 jar: $GAME_JAR" >&2; exit 2; fi
if [ -n "$EXTRA_CP" ] && [ ! -f "$EXTRA_CP" ]; then echo "找不到 $EXTRA_CP" >&2; exit 2; fi

CP="$GAME_JAR${EXTRA_CP:+:$EXTRA_CP}"

mkdir -p "$HERE/build"
echo "[verify] 编译测试类..."
javac -nowarn -cp "$CP" -d "$HERE/build" "$HERE"/tests/*.java || exit 1

echo "[verify] 跑 $test（$data）"
exec java -cp "$CP:$HERE/build" "$@" "$test" "$data"
