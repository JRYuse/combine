#!/usr/bin/env bash
# 编 **aarch64 Linux** 的 arc SDL native（libsdl-arcarm64.so）。
#
# 为什么需要它：官方/自编的桌面 jar 里只有 linux-x64、Windows、macOS 的 SDL native，
# 没有 aarch64 Linux 的；而本机（Android proot / Arch Linux ARM）就是 aarch64，
# 想跑真客户端（截图验证 UI）就必须自己编一个。
#
# 产物：verify/native/libsdl-arcarm64.so（已经编好提交在仓库里，正常不用重跑）
#
# 依赖：pacman -S --needed gcc make cmake sdl2 glu libxi libxss mesa xorg-server-xvfb
#       还要把主机的 gcc 伪装成 jnigen 期待的交叉工具链名：
#         for t in gcc g++ cpp ar strip; do ln -sf "$(which $t)" /usr/local/bin/aarch64-linux-gnu-$t; done
#
# 关键改动（curl 下来的 Arc 源码不在仓库里，这个脚本会自动打补丁）：
#   1) backends/backend-sdl/build.gradle 只留 Linux ARM64 目标、去掉 mac/win
#   2) 加 -DGLEW_EGL 并链接 -lEGL：离屏跑用的是 EGL 上下文，GLEW 默认走 GLX 会初始化失败
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/tmp/arc-native}"
ARC_HASH="${ARC_HASH:-5a9696f1d4}"     # Mindustry v160.1 用的 Arc 提交（见 Mindustry/gradle.properties 的 archash）

mkdir -p "$WORK"
cd "$WORK"

if [ ! -d "Arc-$ARC_HASH" ]; then
  echo "[native] 下载 Arc@$ARC_HASH"
  curl -sL --retry 2 -o arc.tar.gz "https://codeload.github.com/Anuken/Arc/tar.gz/$ARC_HASH"
  tar xzf arc.tar.gz
fi
cd "Arc-$ARC_HASH"

# Gradle wrapper 走腾讯镜像（services.gradle.org 在这台机器上连不上；本机已有 9.3.1 缓存）
python3 - <<'PY'
p='gradle/wrapper/gradle-wrapper.properties'
s=open(p).read()
s=s.replace('https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip',
            'https\\://mirrors.cloud.tencent.com/gradle/gradle-9.3.1-bin.zip')
open(p,'w').write(s)

# 只留 Linux ARM64 目标 + GLEW_EGL
python3 - <<'PY'
p='backends/backend-sdl/build.gradle'
s=open(p,encoding='utf-8').read()
if '【本机改造】' not in s:
    s=s.replace('        headerDirs = ["$mainRoot/glew-2.2.0/include"]\n',
                '        headerDirs = ["$mainRoot/glew-2.2.0/include"]\n'
                '        cFlags += ["-DGLEW_EGL"]\n        cppFlags += ["-DGLEW_EGL"]\n')
    start=s.index('    addLinux(x64, x86){')
    end=s.index('}', s.index('linkerFlags', start))+1
    s=s[:start]+('    // 【本机改造】只编 Linux ARM64（aarch64）\n'
                 '    addLinux(x64, ARM){\n'
                 '        cppFlags += execCmd("sdl2-config --cflags").split(" ")\n'
                 '        cFlags = cppFlags\n'
                 '        libraries = (execCmd("sdl2-config --libs") + " -Wl,-Bdynamic -lGL -lEGL").split(" ")\n'
                 '        linkerFlags = "-shared -fPIC".split(" ")\n'
                 '    }\n')+s[end:]
    # 去掉 mac 目标块（本机编不了）
    i=s.find('    if(System.getProperty("os.arch") != "aarch64"){')
    if i>=0:
        d=0; j=i
        while True:
            if s[j]=='{': d+=1
            elif s[j]=='}':
                d-=1
                if d==0: break
            j+=1
        s=s[:i]+'    // 【本机改造】mac/windows 目标已移除\n'+s[j+1:]
    open(p,'w',encoding='utf-8').write(s)
PY

# glew 源码（jnigen 的 preJni 有时下不下来，手动放好）
mkdir -p backends/backend-sdl/build/jnigen/sources
if [ ! -d backends/backend-sdl/build/jnigen/sources/glew-2.2.0 ]; then
  echo "[native] 下载 glew 2.2.0"
  (cd backends/backend-sdl/build/jnigen/sources &&
   curl -sL --retry 2 -o glew.zip "https://github.com/nigels-com/glew/releases/download/glew-2.2.0/glew-2.2.0.zip" && unzip -q -o glew.zip)
fi

echo "[native] 编（jnigen）..."
./gradlew --console=plain :backends:backend-sdl:jnigenBuildLinux_Arm_64

SO="backends/backend-sdl/libs/linuxarm64/libsdl-arcarm64.so"
[ -f "$SO" ] || { echo "没编出 $SO" >&2; exit 1; }
cp "$SO" "$HERE/libsdl-arcarm64.so"
ls -l "$HERE/libsdl-arcarm64.so"
echo "[native] 好了。跑真客户端：verify/run-client.sh vanilla <数据目录> list"
