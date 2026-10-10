# 跑**真客户端**（本机是 Windows）：不需要 Xvfb —— 那是 X11 的东西，Windows 上不存在；
# 官方桌面 jar 自带 windows 原生（sdl-arc64.dll / arc64.dll），直接开窗口跑就行。
#
#   verify/run-client.ps1 -Game vanilla -Data <数据目录> -Mode list
#   verify/run-client.ps1 -Game mx      -Data <数据目录> -Mode coop
#   verify/run-client.ps1 -Game <某个jar路径> -Data <数据目录> -Mode gen
#
# 截图落到**桌面** shots\（按 001_ 002_ 连续编号，跨次运行不会互相覆盖）。
# -Mode 的取值见 verify/client/Driver.java 顶部；驱动 mod 会把界面开好、截图、然后自己退出。
# 游戏 jar 默认取 Downloads 里的：Mindustry.jar / MindustryX-*-Desktop.jar（可用参数覆盖）。
param(
  [string]$Game = 'vanilla',
  [string]$Data = '',
  [string]$Mode = 'list',
  [string]$Out = '',
  [string]$JavaHome = '',
  [int]$TimeoutSec = 300
)

$ErrorActionPreference = 'Stop'

$ROOT = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$HERE = Join-Path $ROOT 'verify'
if (-not $Data) { $Data = Join-Path $env:TEMP 'combine-verify\data' }
if (-not $Out) { $Out = Join-Path ([Environment]::GetFolderPath('Desktop')) 'shots' }

# ---- JDK ----
if (-not $JavaHome) {
  foreach ($c in @($env:JAVA_HOME, 'C:\Program Files\Java\jdk-21.0.12')) {
    if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) { $JavaHome = $c; break }
  }
}
if (-not $JavaHome) { throw '找不到 JDK：设 JAVA_HOME（或 -JavaHome）再跑' }
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'

# ---- 游戏 jar ----
if ($Game -eq 'vanilla') {
  $src = Join-Path $env:USERPROFILE 'Downloads\Mindustry.jar'
} elseif ($Game -eq 'mx') {
  $src = (Get-ChildItem (Join-Path $env:USERPROFILE 'Downloads') -Filter 'MindustryX-*-Desktop.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
} else {
  $src = $Game
}
if (-not $src -or -not (Test-Path $src)) { throw "找不到游戏 jar: $src" }

# Start-Process 的 -ArgumentList 不会自动加引号：路径带空格就会把参数拆坏
foreach ($p in @($Data, $Out, $src)) {
  if ($p -match '\s') { throw "路径里有空格，这个脚本拼命令行会错：$p（换个没空格的目录）" }
}

New-Item -ItemType Directory -Force -Path (Join-Path $HERE 'build'), $Out, (Join-Path $Data 'mods') | Out-Null

# ---- 防呆：build/libs/combine.jar 比数据目录里的新 → 覆盖过去（和 run-client.sh 一样） ----
$modJar = Join-Path $ROOT 'build\libs\combine.jar'
$modJarData = Join-Path $Data 'mods\combine.jar'
if ((Test-Path $modJar) -and (Test-Path $modJarData) -and
  ((Get-Item $modJar).LastWriteTime -gt (Get-Item $modJarData).LastWriteTime)) {
  Write-Host '[verify] 同步新编的 combine.jar → 数据目录 mods\'
  Copy-Item -Force $modJar $modJarData
}

# ---- 驱动 mod：编译 client\*.java → drv.jar ----
Write-Host '[verify] 编译驱动 mod...'
$drvDir = Join-Path $HERE 'build\drv'
New-Item -ItemType Directory -Force -Path $drvDir | Out-Null
Copy-Item -Force (Join-Path $HERE 'client\mod.hjson') (Join-Path $drvDir 'mod.hjson')
$drvSrc = @(Get-ChildItem (Join-Path $HERE 'client') -Filter *.java | ForEach-Object { $_.FullName })
& $javac -nowarn -cp $src -d $drvDir @drvSrc
if ($LASTEXITCODE -ne 0) { throw '驱动 mod 编译失败' }

$drvJar = Join-Path $HERE 'build\drv.jar'
$drvZip = Join-Path $HERE 'build\drv.zip'
Remove-Item -Force -ErrorAction SilentlyContinue $drvJar, $drvZip
Compress-Archive -Path (Join-Path $drvDir 'mod.hjson'), (Join-Path $drvDir 'drv') -DestinationPath $drvZip -Force
Move-Item -Force $drvZip $drvJar
# 自检：条目必须是 mod.hjson + drv/…，不然游戏会当成坏模组跳过（"改了没生效"多半是这个）
$entries = @(& (Join-Path $JavaHome 'bin\jar.exe') tf $drvJar)
if (($entries -notcontains 'mod.hjson') -or -not ($entries | Where-Object { $_ -match '^drv/.+\.class$' })) {
  $entries | ForEach-Object { Write-Host "  $_" }
  throw 'drv.jar 打包不对（见上面条目）'
}
Copy-Item -Force $drvJar (Join-Path $Data 'mods\drv.jar')

# ---- 客户端崩在模组初始化中途时，Mindustry 会把模组记成 mod-xxx-failed 写进 settings，
#      下次进游戏直接跳过这个模组 —— 跑之前先把那份 settings 挪走（和 run-client.sh 一样）。 ----
foreach ($f in 'settings.bin', 'settings_backup.bin') {
  $p = Join-Path $Data $f
  if (Test-Path $p) { Move-Item -Force $p "$p.bak-prev" }
}

Write-Host "[verify] 跑客户端 mode=$Mode（$((Get-Item $src).Name)），截图输出到 $Out"
$jvmArgs = @("-Ddrv.mode=$Mode", "-Ddrv.out=$Out", "-Dmindustry.data.dir=$Data")
if ($env:DRV_UISCALE) { $jvmArgs += "-Ddrv.uiscale=$env:DRV_UISCALE" }
$jvmArgs += @('-jar', $src)
$proc = Start-Process -FilePath $java -ArgumentList $jvmArgs -PassThru -NoNewWindow
if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
  Write-Host "[verify] $TimeoutSec 秒还在跑，强杀"
  $proc.Kill()
}
Write-Host '[verify] 截图：'
Get-ChildItem $Out -Filter *.png -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime | Select-Object -Last 8 | ForEach-Object { "  $($_.FullName)" }
