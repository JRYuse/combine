#!/usr/bin/env bash
# 跑**真客户端**（离屏 Xvfb + Mesa 软渲染），自动截图。UI / 面板 / 绘制这类改动必须这么验。
#
#   verify/run-client.sh <mx|vanilla|jar路径> <数据目录> [list|coop]
#     list → 开设置→点进"组合工厂"页→截图（看列表/按钮布局）
#     coop → 加载地图、放两台扩展建筑、开 CoopPanel、往池子里加东西→截图（看面板是否实时刷新）
#
# 前提（这台 aarch64 proot 上已经装好，换机器要重来一遍，见 native/build-sdl-native.sh）：
#   pacman -S xorg-server-xvfb mesa libxi libxss glu
#   aarch64 Linux 的 SDL native（官方 jar 里没有）→ verify/native/libsdl-arcarm64.so
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HERE="$ROOT/verify"
MINDUSTRY_JAR="${MINDUSTRY_JAR:-$HOME/Mindustry/desktop/build/libs/Mindustry.jar}"
MINDX_JAR="${MINDX_JAR:-/root/sd/x.jar}"
NATIVE="${NATIVE:-$HERE/native/libsdl-arcarm64.so}"
DISPLAY_NUM="${DISPLAY_NUM:-:99}"
DRV_OUT="${DRV_OUT:-$HOME/sd/shots}"   # 截图统一放 ~/sd/shots（按 001_ 002_ 序号排）

if [ $# -lt 3 ]; then
  echo "用法: $0 <mx|vanilla|jar路径> <数据目录> [list|coop]" >&2
  exit 2
fi
game="$1"; data="$2"; mode="${3:-list}"

case "$game" in
  mx)      SRC_JAR="$MINDX_JAR" ;;
  vanilla) SRC_JAR="$MINDUSTRY_JAR" ;;
  *)       SRC_JAR="$game" ;;
esac
[ -f "$SRC_JAR" ] || { echo "找不到游戏 jar: $SRC_JAR" >&2; exit 2; }
[ -f "$NATIVE" ] || { echo "找不到 $NATIVE（先跑 native/build-sdl-native.sh）" >&2; exit 2; }

mkdir -p "$HERE/build" "$DRV_OUT" "$data/mods"

# 客户端被强杀/崩在模组初始化中途时，Mindustry 会把模组记成 mod-xxx-failed（写进数据目录的
# settings），下次进游戏这个模组就被跳过 —— 会把之后所有测试都带偏。跑之前先把那份 settings 挪走。
for f in settings.bin settings_backup.bin; do
  [ -f "$data/$f" ] && mv "$data/$f" "$data/$f.bak-prev"
done

# 1) 驱动 mod（自动开页面 / 开面板 / 截图）
echo "[verify] 编译驱动 mod..."
javac -nowarn -cp "$SRC_JAR" -d "$HERE/build/drv" "$HERE/client/Driver.java" || exit 1
cp "$HERE/client/mod.hjson" "$HERE/build/drv/"
(cd "$HERE/build/drv" && rm -f "$HERE/build/drv.jar" && zip -q -r "$HERE/build/drv.jar" mod.hjson drv)
cp "$HERE/build/drv.jar" "$data/mods/drv.jar"

# 2) 游戏 jar + aarch64 SDL native（原生 jar 里没有这个平台的）
echo "[verify] 注入 native -> $HERE/build/game.jar"
cp "$SRC_JAR" "$HERE/build/game.jar"
(cd "$HERE/native" && zip -q -g "$HERE/build/game.jar" libsdl-arcarm64.so)   # 条目名必须是 libsdl-arcarm64.so（arc 按这个名找）

# 3) Xvfb（离屏 X，SDL 要一个显示；GL 走 EGL/llvmpipe）
X_SOCK="/tmp/.X11-unix/X${DISPLAY_NUM#:}"
if [ ! -e "$X_SOCK" ]; then
  echo "[verify] 启动 Xvfb $DISPLAY_NUM"
  nohup Xvfb "$DISPLAY_NUM" -screen 0 1280x800x24 -nolisten tcp > "$HERE/build/xvfb.log" 2>&1 &
  sleep 3
fi

# 4) 跑客户端（SDL_VIDEODRIVER=offscreen：没有 X 窗口，但 EGL 上下文可用）
echo "[verify] 跑客户端 mode=$mode，截图输出到 $DRV_OUT"
DISPLAY="$DISPLAY_NUM" SDL_VIDEODRIVER=offscreen \
  java -Ddrv.mode="$mode" -Ddrv.out="$DRV_OUT" ${DRV_UISCALE:+-Ddrv.uiscale=$DRV_UISCALE} -Dmindustry.data.dir="$data" \
  -jar "$HERE/build/game.jar"
echo "[verify] 截图："; ls -l "$DRV_OUT" | tail -8
