#!/usr/bin/env bash
# 造"用户复现存档"的数据目录（stainedMountains / sector-serpulo-223）：
# 存档 + 它需要的模组 + 本仓库的 combine.jar。
#
#   verify/make-repro-dataset.sh [~/sd/a/save] [/tmp/mp_rep2/data]
#
# 用途：verify/run-client.sh mx <数据目录> rep   —— 连续存读档，看物品总量会不会涨。
#
# 注意：这套模组在 headless 里跑不起来（ve 要建 GL shader、饱和火力某版是 Java 25 字节码），
# 只能跑真客户端。饱和火力用 ~/sd/饱和火力3.4.4.3.jar（Java 17，能加载；版本比存档高一点点）。
set -uo pipefail

SRC="${1:-$HOME/sd/a/save}"
DEST="${2:-/tmp/mp_rep2/data}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMBINE_JAR="${COMBINE_JAR:-$ROOT/build/libs/combine.jar}"
VE_PATCHED="${VE_PATCHED:-/tmp/ve-patched.zip}"
VPATCH_ROOT="${VPATCH_ROOT:-/tmp/vepatch2}"

[ -f "$COMBINE_JAR" ] || { echo "先 ./gradlew --offline deploy（没找到 $COMBINE_JAR）" >&2; exit 2; }
[ -d "$SRC" ] || { echo "找不到源目录 $SRC" >&2; exit 2; }

mkdir -p "$DEST/mods" "$DEST/saves"

# 1) ve（vanilla-expansion）：脚本里写死了 Vars.renderer.maxZoom，headless/无 renderer 会崩；打个补丁副本
if [ ! -f "$VE_PATCHED" ]; then
  mkdir -p "$VPATCH_ROOT"
  ( cd "$VPATCH_ROOT" && unzip -q -o "$SRC/mods/martian238vanilla-expansion-mod1.zip" )
  python3 - "$VPATCH_ROOT/scripts/main.js" <<'PY'
import io, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8').read()
s = s.replace("Vars.renderer.maxZoom = 25;", "if (Vars.renderer != null) { Vars.renderer.maxZoom = 25; }")
s = s.replace("Vars.renderer.minZoom = 0.2;", "if (Vars.renderer != null) { Vars.renderer.minZoom = 0.2; }")
io.open(p, 'w', encoding='utf-8').write(s)
PY
  ( cd "$VPATCH_ROOT" && zip -q -r "$VE_PATCHED" . )
fi
cp "$VE_PATCHED" "$DEST/mods/ve-patched.zip"

# 2) 其余模组
for m in sakuralillypowered-by-nature.zip mindustry-balancingserp-dpstorage.zip sputnucupgrated-tiers.zip; do
  cp "$SRC/mods/$m" "$DEST/mods/$m"
done
cp "$HOME/sd/饱和火力3.4.4.3.jar" "$DEST/mods/" 2>/dev/null || echo "[warn] 没找到 ~/sd/饱和火力3.4.4.3.jar"

# 3) 存档 + 本模组
cp "$SRC/saves/sector-serpulo-223.msav" "$DEST/saves/" 2>/dev/null
cp "$SRC/saves/sector-serpulo-223.msav-backup.msav" "$DEST/saves/" 2>/dev/null
cp "$COMBINE_JAR" "$DEST/mods/combine.jar"

echo "[verify] 复现数据目录好了：$DEST"
ls -l "$DEST/mods" "$DEST/saves"
echo "[verify] 跑：verify/run-client.sh mx $DEST rep"
