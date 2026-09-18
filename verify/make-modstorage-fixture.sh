#!/usr/bin/env bash
# 造一个"别的模组用 js/java 写的仓库"测试模组（具名 StorageBlock 子类，容量 5000），
# 用来跑 verify/tests/ModStorageCoreTest —— 验证"别的模组仓库挨着核心时照样给核心扩容"。
#
#   verify/make-modstorage-fixture.sh <数据目录>
#   verify/run-headless.sh mx <数据目录> combine.dbg.ModStorageCoreTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MINDUSTRY_JAR="${MINDUSTRY_JAR:-$HOME/Mindustry/desktop/build/libs/Mindustry.jar}"
DATA="${1:?用法: $0 <数据目录>}"
WORK="${WORK:-/tmp/modstorage-fixture}"

[ -f "$MINDUSTRY_JAR" ] || { echo "找不到原版 jar: $MINDUSTRY_JAR" >&2; exit 2; }

rm -r "$WORK" 2>/dev/null || true
mkdir -p "$WORK/src/modstorage" "$WORK/classes" "$DATA/mods"

cat > "$WORK/src/modstorage/ModStorageMod.java" <<'EOF'
package modstorage;
import mindustry.content.Items;
import mindustry.mod.Mod;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.meta.BuildVisibility;

/** 具名 StorageBlock 子类（等价于 js 的 extend(StorageBlock, ...)），组合工厂不该替换它。 */
public class ModStorageMod extends Mod{
    public static class ModCargo extends StorageBlock{
        public ModCargo(String name){ super(name); }
    }
    @Override public void loadContent(){
        ModCargo cargo = new ModCargo("mod-cargo");
        cargo.size = 4;
        cargo.hasItems = true;
        cargo.unloadable = true;
        cargo.health = 3100;
        cargo.itemCapacity = 5000;
        cargo.buildVisibility = BuildVisibility.shown;
        cargo.category = Category.effect;
        cargo.requirements(Category.effect, ItemStack.with(Items.thorium, 150, Items.plastanium, 90));
    }
}
EOF

cat > "$WORK/src/mod.hjson" <<'EOF'
name: "modstorage"
displayName: "mod storage fixture"
author: "verify"
description: "具名 StorageBlock 子类，用来验证模组仓库的核心扩容"
version: "1.0"
minGameVersion: "146"
main: "modstorage.ModStorageMod"
EOF

javac -nowarn -cp "$MINDUSTRY_JAR" -d "$WORK/classes" "$WORK/src/modstorage/ModStorageMod.java"
cp "$WORK/src/mod.hjson" "$WORK/classes/"
(cd "$WORK/classes" && zip -q -r -FS "$DATA/mods/modstorage.jar" mod.hjson modstorage)
echo "[fixture] 好了：$DATA/mods/modstorage.jar"
