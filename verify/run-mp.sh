#!/usr/bin/env bash
# 真联机验证：一个 headless **专用服务器**进程 + 一个**真客户端**（Xvfb 软渲染）连上去，
# 服务端按剧本 造单位 → 融合成组合巨兽 → 解体 → 再融合，客户端每秒记录自己看到的世界，
# 最后比对"服务端出现过的每种状态，客户端是不是都见过"（漏了 = 单位没同步/幽灵/看不见）。
#
#   verify/run-mp.sh <mx|jar路径> <数据目录> [延迟ms] [丢包%] [脚本秒数]
#     verify/run-mp.sh official /tmp/mp_cj/data            # 本地链路（无延迟无丢包）
#     verify/run-mp.sh official /tmp/mp_cj/data 200 20     # 200ms 延迟 + 20% 快照丢包
#
# 延迟/丢包由 verify/lagnet.py 那个 **socket 代理**加（客户端连代理端口，代理转发到服务端端口）：
# UDP（实体/方块/状态快照）按丢包率丢 + 加延迟，TCP 只加延迟不丢。为什么不做在模组里见 lagnet.py 头注释。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HERE="$ROOT/verify"
MINDX_JAR="${MINDX_JAR:-/root/sd/x.jar}"
SERVER_JAR="${SERVER_JAR:-$HOME/sd/server-release.jar}"
MINDUSTRY_JAR="${MINDUSTRY_JAR:-$HOME/Mindustry/desktop/build/libs/Mindustry.jar}"
NATIVE="${NATIVE:-$HERE/native/libsdl-arcarm64.so}"
DISPLAY_NUM="${DISPLAY_NUM:-:99}"
DRV_OUT="${DRV_OUT:-$HOME/sd/shots}"

if [ $# -lt 2 ]; then
  echo "用法: $0 <mx|vanilla|jar路径> <数据目录> [延迟ms] [丢包%] [脚本秒数]" >&2
  exit 2
fi
game="$1"; data="$2"; latency="${3:-0}"; loss="${4:-0}"; seconds="${5:-120}"

# MP_VERBOSE=1 → 两端每秒把每个单位逐行打出来（排查"少看到某个单位/幽灵"用，平时别开：一次几千行）
VERBOSE_ARG=""
[ "${MP_VERBOSE:-0}" = "1" ] && VERBOSE_ARG="-Ddrv.mpVerbose=1"

OFFICIAL_JAR="${OFFICIAL_JAR:-/tmp/mind160.jar}"   # 官方 160.1 客户端；自定义构建会被官方服务端拒
case "$game" in
  official) SRC_JAR="$OFFICIAL_JAR"; SRV_KIND=official ;;
  mx)       SRC_JAR="$MINDX_JAR";    SRV_KIND=custom ;;
  vanilla)  SRC_JAR="$MINDUSTRY_JAR"; SRV_KIND=custom ;;
  *)        SRC_JAR="$game";         SRV_KIND=custom ;;
esac
[ -f "$SRC_JAR" ] || { echo "找不到游戏 jar: $SRC_JAR" >&2; exit 2; }
[ -f "$SERVER_JAR" ] || { echo "找不到服务端 jar: $SERVER_JAR（放到 ~/sd/server-release.jar）" >&2; exit 2; }
[ -f "$NATIVE" ] || { echo "找不到 $NATIVE（先跑 native/build-sdl-native.sh）" >&2; exit 2; }

PORT="${MP_PORT:-6567}"
PROXY_PORT="${MP_PROXY_PORT:-$((PORT + 1))}"   # 有延迟/丢包时客户端连这个端口
SRV_DIR="${SRV_DIR:-$data-mp-server}"     # 官方服务端的运行目录（里面放 config/）
HOST_LOG="$HERE/build/mp-host.log"
CLIENT_LOG="$HERE/build/mp-client.log"
LAG_LOG="$HERE/build/mp-lag.log"

mkdir -p "$HERE/build" "$DRV_OUT" "$data/mods"

# 0) 编译模组（安卓兼容命令）+ 驱动 mod + 测试类
if [ "${MP_SKIP_BUILD:-0}" != "1" ]; then
  echo "[mp] 编译 combine（./gradlew --offline deploy）"
  (cd "$ROOT" && ./gradlew --offline deploy -q) || exit 1
fi
cp "$ROOT/build/libs/combine.jar" "$data/mods/combine.jar"

echo "[mp] 编译驱动 mod（含链路模拟）"
mkdir -p "$HERE/build/drv"
javac -nowarn -cp "$SRC_JAR" -d "$HERE/build/drv" "$HERE"/client/*.java || exit 1
cp "$HERE/client/mod.hjson" "$HERE/build/drv/"
(cd "$HERE/build/drv" && rm -f "$HERE/build/drv.jar" && zip -q -r "$HERE/build/drv.jar" mod.hjson drv)
cp "$HERE/build/drv.jar" "$data/mods/drv.jar"

# 1) 服务端运行目录：官方 server-release.jar 认的是 <运行目录>/config（mods/maps/…），
#    模组要和客户端完全一致（不然握手就被踢）；地图用原版自带的 Archipelago。
mkdir -p "$SRV_DIR/config/mods" "$SRV_DIR/config/maps"
cp "$ROOT/build/libs/combine.jar" "$SRV_DIR/config/mods/combine.jar"
cp "$HERE/build/drv.jar" "$SRV_DIR/config/mods/drv.jar"
# 服务端和客户端的模组清单必须**一字不差**，否则握手就被踢（Incompatible mods）。
# 注意别只拷 *.jar：测试数据集里的模组有**目录形式**的（废土科技 = 解压开的模组目录），
# 只按 .jar 拷会让服务端少一个模组 → 客户端带着它进来就被当"多余模组"踢掉。
for m in "$data"/mods/*; do
  [ -e "$m" ] || continue
  case "$(basename "$m")" in
    combine.jar|drv.jar) ;;
    *) cp -r "$m" "$SRV_DIR/config/mods/" 2>/dev/null ;;
  esac
done
cp /root/Mindustry/core/assets/maps/default/archipelago.msav "$SRV_DIR/config/maps/" 2>/dev/null || \
  cp "$HOME/Mindustry/core/assets/maps/default/archipelago.msav" "$SRV_DIR/config/maps/" 2>/dev/null
cp -r "$data"/maps/. "$SRV_DIR/config/maps/" 2>/dev/null

# 自定义 host 那条路（mx/vanilla）：headless 起服 + 测试类里的剧本，需要先编译测试类
if [ "$SRV_KIND" = "custom" ]; then
  echo "[mp] 编译测试类"
  javac -nowarn -cp "$SRC_JAR:$SERVER_JAR" -d "$HERE/build" "$HERE"/tests/*.java || exit 1
fi

# 3) Xvfb（客户端要一个显示）
#    注意：不能用"socket 文件在不在"判断 —— 上一次跑被打断后 socket 会留下来，
#    但 Xvfb 进程已经没了，光看文件会以为显示还在，客户端直接起不来。
#    按进程判断，socket 是死的就先删掉。
X_SOCK="/tmp/.X11-unix/X${DISPLAY_NUM#:}"
if ! pgrep -f "Xvfb $DISPLAY_NUM( |$)" >/dev/null 2>&1; then
  [ -e "$X_SOCK" ] && rm -f "$X_SOCK"
  echo "[mp] 启动 Xvfb $DISPLAY_NUM"
  nohup Xvfb "$DISPLAY_NUM" -screen 0 1280x800x24 -nolisten tcp > "$HERE/build/xvfb.log" 2>&1 &
  sleep 3
fi

# 2) 起服务端
echo "[mp] 起服务端（$SRV_KIND）port=$PORT 延迟=${latency}ms 丢包=${loss}%（日志 $HOST_LOG）"
rm -f "$HOST_LOG"
if [ "$SRV_KIND" = "official" ]; then
  # 官方专用服务器：用 FIFO 喂 stdin（发 host 命令），exec 让子 shell 变成 java —— 这样 $! 就是 java 的 PID，
  # 脚本结束能干净地杀掉它（否则端口会一直被占着，下一次跑就 "Port in use"）。
  FIFO="$HERE/build/mp-host.stdin"
  rm -f "$FIFO"; mkfifo "$FIFO"
  ( sleep $((seconds + 180)) > "$FIFO" ) &
  FEEDER_PID=$!
  ( cd "$SRV_DIR" && exec java -Ddrv.scenario=1 -Ddrv.latency="$latency" -Ddrv.loss="$loss" $VERBOSE_ARG \
      -jar "$SERVER_JAR" < "$FIFO" ) > "$HOST_LOG" 2>&1 &
  HOST_PID=$!
  # 等服务器把 stdin 读起来再发命令
  for i in $(seq 1 60); do
    grep -q "Server loaded" "$HOST_LOG" 2>/dev/null && break
    sleep 1
  done
  echo "host Archipelago survival" > "$FIFO"
else
  # 自定义 host：x.jar/本地构建的游戏类 + 官方服务端的 headless 后端（两端都是"自定义构建"，互相不挑）
  java -Ddrv.latency="$latency" -Ddrv.loss="$loss" \
    $VERBOSE_ARG -cp "$SRC_JAR:$SERVER_JAR:$HERE/build" combine.dbg.MpHost "$SRV_DIR" "$PORT" "$seconds" > "$HOST_LOG" 2>&1 &
  HOST_PID=$!
fi

echo "[mp] 等服务端就绪…"
ready=0
for i in $(seq 1 90); do
  if grep -qE "Opened a server on port|服务器已就绪" "$HOST_LOG" 2>/dev/null; then ready=1; break; fi
  if ! kill -0 "$HOST_PID" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "[mp] 服务端没起来，日志尾部：" >&2
  tail -30 "$HOST_LOG" >&2
  kill "$HOST_PID" 2>/dev/null
  exit 3
fi
grep -m1 -E "Opened a server on port|服务器已就绪" "$HOST_LOG"

# 4.5) 链路模拟代理（Verify/lagnet.py）：客户端连 PROXY_PORT，代理转发到 PORT
CONNECT_PORT="$PORT"
LAG_PID=""
if awk "BEGIN{exit !($latency > 0 || $loss > 0)}"; then
  CONNECT_PORT="$PROXY_PORT"
  echo "[mp] 起链路模拟代理 :$PROXY_PORT → :$PORT （延迟 ${latency}ms、UDP 丢包 ${loss}%，日志 $LAG_LOG）"
  rm -f "$LAG_LOG"
  python3 "$HERE/lagnet.py" --listen "$PROXY_PORT" --target "$PORT" \
    --latency "$latency" --loss "$loss" --jitter "${MP_JITTER:-50}" --warmup "${MP_WARMUP:-5}" \
    --max-threads "${MP_MAX_THREADS:-64}" \
    > "$LAG_LOG" 2>&1 &
  LAG_PID=$!
  sleep 1
  kill -0 "$LAG_PID" 2>/dev/null || { echo "[mp] 链路模拟代理没起来：" >&2; cat "$LAG_LOG" >&2; kill "$HOST_PID" 2>/dev/null; exit 5; }
fi

# 4.6) 线程数采样：proot 是单线程 ptrace 事件循环，某个进程线程数涨到几百条会把 proot
#      拖成活锁 → 整个容器所有进程 `t (tracing stop)`，连会话本身都冻住（2026-09-22 两次）。
#      lagnet 自己带 --max-threads 自检（超了主动退出），这里再留一份全程证据，跑完打印峰值。
THREADS_LOG="$HERE/build/mp-threads.log"
: > "$THREADS_LOG"
(
  while kill -0 "$HOST_PID" 2>/dev/null; do
    for spec in "lagnet ${LAG_PID:-}" "服务端 $HOST_PID"; do
      set -- $spec
      [ -n "${2:-}" ] || continue
      n=$(awk '/^Threads:/{print $2}' "/proc/$2/status" 2>/dev/null)
      [ -n "$n" ] && echo "$1 $n" >> "$THREADS_LOG"
    done
    sleep 5
  done
) &

# 5) 起真客户端（连过去，边跑边截图）
echo "[mp] 起客户端（日志 $CLIENT_LOG）"
cp "$SRC_JAR" "$HERE/build/game.jar"
(cd "$HERE/native" && zip -q -g "$HERE/build/game.jar" libsdl-arcarm64.so)
for f in settings.bin settings_backup.bin; do
  [ -f "$data/$f" ] && mv "$data/$f" "$data/$f.bak-mp"
done
# 客户端自己会到点 exit，但软渲染下偶发退不干净；超时兜底（TERM 之后 20 秒还不走就 KILL），
# 免得脚本永远卡在这一行、后面的比对永远不执行。
DISPLAY="$DISPLAY_NUM" SDL_VIDEODRIVER=offscreen \
  timeout -k 20 $((seconds + 240)) \
    java -Ddrv.mode=mp -Ddrv.out="$DRV_OUT" -Ddrv.host=127.0.0.1 -Ddrv.port="$CONNECT_PORT" -Ddrv.mpSeconds="$seconds" \
         -Ddrv.latency="$latency" -Ddrv.loss="$loss" \
         $VERBOSE_ARG -Dmindustry.data.dir="$data" -jar "$HERE/build/game.jar" > "$CLIENT_LOG" 2>&1

sleep 2
kill "$HOST_PID" 2>/dev/null
wait "$HOST_PID" 2>/dev/null
[ -n "$LAG_PID" ] && kill "$LAG_PID" 2>/dev/null
[ -n "${FEEDER_PID:-}" ] && kill "$FEEDER_PID" 2>/dev/null
[ -n "${FIFO:-}" ] && rm -f "$FIFO"

# 6) 比对：服务端出现过的每种状态，客户端是不是都见过
echo
echo "[mp] ===== 结果 ====="
# 先看有没有"被踢"：模组清单对不上时服务端会直接踢人，客户端一路跑完但世界里啥都没有，
# 不查出这条会误判成"同步丢了"。
strip_ansi(){ sed -e 's/\x1b\[[0-9;]*m//g'; }
if strip_ansi < "$HOST_LOG" | grep -q "Kicking connection"; then
  echo "[mp] FAIL：服务端把客户端踢了（原因见下），这次结果不算："
  strip_ansi < "$HOST_LOG" | grep "Kicking connection" | tail -3 | sed 's/^/  /'
  exit 6
fi
# 游戏自己的日志行带 ANSI 颜色前缀，先剥掉再匹配（不然客户端的 [MP-STATE] 一条都抓不到）
strip_ansi < "$HOST_LOG" | grep -oE "\[MP-HOST\].*" | tail -12 || true
if [ -s "$LAG_LOG" ]; then
  echo "[mp] 链路模拟代理（最后一次统计）:"
  grep -E "\[lagnet\]" "$LAG_LOG" | tail -3 | sed 's/^/  /'
fi
# 线程数峰值：判断"有没有线程泄漏"的硬指标（lagnet 的正常值是个位数；几百 = 又要拖垮 proot 了）
if [ -s "$THREADS_LOG" ]; then
  echo "[mp] 线程数峰值（proot 活锁防线，lagnet 自检上限 ${MP_MAX_THREADS:-64}）:"
  awk '{if ($2 > m[$1]) m[$1] = $2} END {for (k in m) printf "  %s 峰值=%d\n", k, m[k]}' "$THREADS_LOG"
fi
# 判定只看**阶段边界**上打印的状态（每秒采样会漏掉瞬时态，不算数）
HOST_STATES="$(strip_ansi < "$HOST_LOG" | grep -oE "\[MP-PHASE\] .*" | sed -E 's/^\[MP-PHASE\] [a-zA-Z0-9]+ //' | sort -u)"
HOST_STATES_ALL="$(strip_ansi < "$HOST_LOG" | grep -oE "\[MP-STATE\] .*" | sed 's/^\[MP-STATE\] //' | sort -u)"
CLIENT_STATES="$(strip_ansi < "$CLIENT_LOG" | grep -oE "\[MP-(STATE|CLIENT-STATE)\] .*" \
  | sed -E 's/^\[MP-(STATE|CLIENT-STATE)\] //' | sort -u)"
if [ -z "$HOST_STATES" ]; then
  echo "[mp] FAIL：服务端一条状态都没打出来（剧本没跑起来？）"
  tail -30 "$HOST_LOG"
  exit 4
fi
echo "[mp] 服务端阶段状态（判定用，$(echo "$HOST_STATES" | wc -l) 种）:"
echo "$HOST_STATES" | sed 's/^/  host   /'
echo "[mp] 服务端每秒采样共 $(echo "$HOST_STATES_ALL" | wc -l) 种（含瞬时态，只做参考）"
echo "[mp] 客户端状态（$(echo "$CLIENT_STATES" | wc -l) 种）:"
echo "$CLIENT_STATES" | sed 's/^/  client /'
MISSING="$(comm -23 <(echo "$HOST_STATES") <(echo "$CLIENT_STATES"))"
if [ -n "$MISSING" ]; then
  echo "[mp] FAIL：以下状态服务端有、客户端从没看到（= 没同步过去 / 幽灵 / 看不见）："
  echo "$MISSING" | sed 's/^/  MISSING /'
  exit 1
fi
echo "[mp] PASS：服务端出现过的每种状态客户端都看到了"

# 7) 逐 id 对账（抓"幽灵单位"）：两端每秒都打一行
#      [MP-IDS] ms=<epoch毫秒> player=<玩家单位id> <id:成员数[:成员id,成员id]>...
#    客户端多出来的 id = 服务端没有的实体（幽灵）；少的 = 没同步过去。统计摘要看不出来的
#    "多了一只/少了一只成员"到这里就能抓住（用户报的"客户端合体变幽灵"）。
#    · 按 epoch 毫秒对齐到**同一时刻**（两端在同一台机器上）：客户端一退出，服务端立刻删掉玩家
#      单位，直接比"最后一行"必然假阳性；两端的"游戏秒"也不是同一个钟（服务端按墙上时间排剧本、
#      客户端按帧计时），所以只能用真实时间戳对齐；
#    · player= 那只在比对时剔除：它的生死时机天生比客户端早/晚，不属于要抓的"幽灵单位"。
ids_line(){ strip_ansi < "$1" | grep -oE "\[MP-IDS\].*" | grep -oE "ms=[0-9]+ player=-?[0-9]+.*" | tail -1; }
CLIENT_LINE="$(ids_line "$CLIENT_LOG")"
if [ -z "$CLIENT_LINE" ]; then
  echo "[mp] 注：客户端日志里没有 [MP-IDS] 行，跳过逐 id 对账（驱动是不是没更新？）"
else
  CMS="$(echo "$CLIENT_LINE" | sed -E 's/^ms=([0-9]+).*/\1/')"
  CPLAY="$(echo "$CLIENT_LINE" | sed -E 's/^ms=[0-9]+ player=(-?[0-9]+).*/\1/')"
  # 服务端选**时间上最接近客户端最后一行**的那一行（同一台机器，毫秒可直接比）
  HOST_LINE=""
  HOST_LINE="$(strip_ansi < "$HOST_LOG" | grep -oE "\[MP-IDS\].*" \
    | grep -oE "ms=[0-9]+ player=-?[0-9]+.*" \
    | awk -v cms="$CMS" '{ split($1, a, "="); d = a[2] - cms; if(d < 0) d = -d; if(best == "" || d < best){ best = d; bestline = $0 } } END { if(best <= 5000) print bestline }')"
  if [ -z "$HOST_LINE" ]; then
    echo "[mp] 注：服务端没有和客户端 ms=$CMS 相差 5 秒内的 [MP-IDS] 行，跳过逐 id 对账"
  else
    HPLAY="$(echo "$HOST_LINE" | sed -E 's/^ms=[0-9]+ player=(-?[0-9]+).*/\1/')"
    # 剔除玩家自己那只 + 只留 id 集合
    ids_of(){ echo "$1" | sed -E 's/^ms=[0-9]+ player=-?[0-9]+ //' | tr ' ' '\n' | grep -v '^$' \
      | grep -vE "^(${CPLAY}|${HPLAY}):" | sort; }
    HOST_IDS="$(ids_of "$HOST_LINE")"
    CLIENT_IDS="$(ids_of "$CLIENT_LINE")"
    echo "[mp] ===== 逐 id 对账（两端对齐到同一毫秒，各 $(echo "$CLIENT_IDS" | grep -c .) 个实体，已剔除玩家自己那只）====="
    echo "$CLIENT_IDS" | sed 's/^/  client /'
    GHOST="$(comm -13 <(echo "$HOST_IDS") <(echo "$CLIENT_IDS"))"
    LOST="$(comm -23 <(echo "$HOST_IDS") <(echo "$CLIENT_IDS"))"
    if [ -n "$GHOST" ]; then
      echo "[mp] FAIL：客户端有、服务端没有的实体（= 幽灵单位）："
      echo "$GHOST" | sed 's/^/  GHOST /'
      exit 1
    fi
    if [ -n "$LOST" ]; then
      echo "[mp] FAIL：服务端有、客户端没有的实体（= 没同步过去）："
      echo "$LOST" | sed 's/^/  LOST /'
      exit 1
    fi
    echo "[mp] PASS：两端逐 id 完全一致（没有幽灵、没有丢实体）"

    # 【幽灵成员】同一个 id 不能既作为世界里的独立单位出现、又是某只巨兽的成员 ——
    # 这正是用户报的"客户端进行单位合体会变成幽灵单位"（合体后成员没被摘掉，还站在巨兽旁边）。
    # 逐秒扫两边的每一行：任何一刻出现都算 FAIL。
    ghost_members(){
      awk '{
        n = split($0, tok, " ");
        delete standalone; delete claimed;
        for(i = 1; i <= n; i++){
          t = tok[i];
          if(t == "" || t ~ /^t=/ || t ~ /^player=/) continue;
          split(t, p, ":");
          standalone[p[1]] = 1;
          if(length(p) >= 3 && p[3] != ""){
            m = split(p[3], mem, ",");
            for(j = 1; j <= m; j++) claimed[mem[j]] = 1;
          }
        }
        for(id in claimed) if(id in standalone) print id;
      }' | sort -u | tr '\n' ' '
    }
    HOST_GHOST="$(strip_ansi < "$HOST_LOG" | grep -oE "\[MP-IDS\].*" | sed 's/^\[MP-IDS\] //' | ghost_members)"
    CLIENT_GHOST="$(strip_ansi < "$CLIENT_LOG" | grep -oE "\[MP-IDS\].*" | sed 's/^\[MP-IDS\] //' | ghost_members)"
    if [ -n "$HOST_GHOST" ] || [ -n "$CLIENT_GHOST" ]; then
      echo "[mp] FAIL：同一 id 既是世界里独立单位、又是巨兽成员（幽灵成员）："
      [ -n "$HOST_GHOST" ] && echo "  服务端: $HOST_GHOST"
      [ -n "$CLIENT_GHOST" ] && echo "  客户端: $CLIENT_GHOST"
      exit 1
    fi
    echo "[mp] PASS：全程没有幽灵成员（没有任何 id 同时是独立单位与巨兽成员）"
  fi
fi

# 8) 物品总量对账（抓"组合节点瞎连 → 物品暴涨/变负数"）：两端每秒各打一行
#      [MP-ITEMS] ms=<epoch> total=<按模块身份去重的世界物品总量>
#    判定两条：①两端数量必须一样（客户端看到的就是玩家看到的）；②全程不能出现负数。
items_line(){ strip_ansi < "$1" | grep -oE "\[MP-ITEMS\].*ms=[0-9]+ total=-?[0-9]+" | tail -1; }
CLIENT_ITEMS="$(items_line "$CLIENT_LOG")"
HOST_ITEMS="$(items_line "$HOST_LOG")"
if [ -z "$HOST_ITEMS" ] || [ -z "$CLIENT_ITEMS" ]; then
  echo "[mp] 注：日志里没有 [MP-ITEMS] 行，跳过物品总量对账"
else
  CALL="$(strip_ansi < "$CLIENT_LOG" | grep -oE "\[MP-ITEMS\] ms=[0-9]+ total=-?[0-9]+" | sed 's/.*total=//')"
  HALL="$(strip_ansi < "$HOST_LOG"   | grep -oE "\[MP-ITEMS\] ms=[0-9]+ total=-?[0-9]+" | sed 's/.*total=//')"
  CNEG="$(echo "$CALL" | grep -c '^-')"
  HNEG="$(echo "$HALL" | grep -c '^-')"
  echo "[mp] ===== 物品总量（按模块身份去重；客户端 $CLIENT_ITEMS / 服务端 $HOST_ITEMS）====="
  if [ "$CNEG" != "0" ] || [ "$HNEG" != "0" ]; then
    echo "[mp] FAIL：出现过负数物品总量（客户端 $CNEG 次、服务端 $HNEG 次）"
    exit 1
  fi
  CM="$(echo "$CLIENT_ITEMS" | sed -E 's/.*ms=([0-9]+).*/\1/')"
  HOST_ALIGNED="$(strip_ansi < "$HOST_LOG" | grep -oE "\[MP-ITEMS\] ms=[0-9]+ total=-?[0-9]+" \
    | awk -v cms="$CM" '{ split($2, a, "="); d = a[2] - cms; if(d < 0) d = -d; if(best == "" || d < best){ best = d; line = $0 } } END { if(best <= 5000) print line }')"
  if [ -z "$HOST_ALIGNED" ]; then
    echo "[mp] 注：服务端没有和客户端同一时刻的 [MP-ITEMS] 行，跳过两端数量比对"
  else
    CV="$(echo "$CLIENT_ITEMS" | sed 's/.*total=//')"
    HV="$(echo "$HOST_ALIGNED" | sed 's/.*total=//')"
    # 容差：客户端比服务端晚一个快照（200ms 链路 + 20% 丢包），几十个物品的差是正常的；
    # 但"客户端 11m / 服务端几千"这种量级差必须判失败（用户报的异常增长就是这一条）。
    TOL=$(( HV / 20 + 50 ))
    DIFF=$(( CV - HV )); [ "$DIFF" -lt 0 ] && DIFF=$(( -DIFF ))
    echo "[mp]   客户端=$CV 服务端=$HV 差=$DIFF（容差 $TOL）"
    if [ "$DIFF" -gt "$TOL" ]; then
      echo "[mp] FAIL：同一时刻两端物品总量差得太多（客户端 $CV / 服务端 $HV）—— 客户端那份池子被算岔了"
      exit 1
    fi
    echo "[mp] PASS：同一时刻两端物品总量基本一致（客户端 $CV / 服务端 $HV），且全程没有负数"
  fi
fi
echo "[mp] 截图："; ls -l "$DRV_OUT" | tail -5
