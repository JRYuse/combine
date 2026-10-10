# 交付模组（本机是 Windows）：编译（**必须兼容安卓**）→ 检查调试残留 → 把 jar 放到**桌面**
#
#   verify/deliver.ps1
#   verify/deliver.ps1 -Out 'D:\别的地方'
#
# 为什么必须用 deploy：`./gradlew jar` 只出纯桌面包；`./gradlew deploy` = desktop + android 合并，
# 产物里有 classes.dex，安卓端才能装。对应 verify/deliver.sh（那是 Linux/Termux 版）。
param(
  [string]$Out = '',
  [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'

$ROOT = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Out) { $Out = [Environment]::GetFolderPath('Desktop') }
if (-not $JavaHome) {
  foreach ($c in @($env:JAVA_HOME, 'C:\Program Files\Java\jdk-21.0.12')) {
    if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) { $JavaHome = $c; break }
  }
}
if (-not $JavaHome) { throw '找不到 JDK：设 JAVA_HOME（或 -JavaHome）再跑' }
$env:JAVA_HOME = $JavaHome

Write-Host '[deliver] 1/3 编译（兼容安卓：gradlew --offline deploy）'
Push-Location $ROOT
try {
  & (Join-Path $ROOT 'gradlew.bat') --offline deploy
  if ($LASTEXITCODE -ne 0) { throw '编译失败' }
} finally {
  Pop-Location
}

$jar = Join-Path $ROOT 'build\libs\combine.jar'
if (-not (Test-Path $jar)) { throw "没编出 $jar" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try {
  if (-not ($zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' })) {
    throw '产物里没有 classes.dex —— 这不是安卓可用的包（是不是只跑了 jar？）'
  }
} finally {
  $zip.Dispose()
}

Write-Host '[deliver] 2/3 检查调试残留'
$leftover = @(Get-ChildItem (Join-Path $ROOT 'src') -Recurse -Filter *.java |
  Select-String -Pattern '\[dbg\]|dbgTicks|System\.out\.print|Log\.info\("\[drv\]')
if ($leftover.Count -gt 0) {
  Write-Host '还有调试代码，先删掉再交付：'
  $leftover | ForEach-Object { Write-Host "  $($_.Path):$($_.LineNumber): $($_.Line.Trim())" }
  throw '有调试残留'
}

Write-Host "[deliver] 3/3 放到 $Out"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
Copy-Item -Force $jar (Join-Path $Out 'combine.jar')
# 只放 combine.jar —— 带版本号的文件名由使用者自己改，脚本不生成
Get-Item (Join-Path $Out 'combine.jar') | Select-Object FullName, Length, LastWriteTime
Write-Host '[deliver] 好了：桌面\combine.jar（安卓 + 电脑都能装）'
