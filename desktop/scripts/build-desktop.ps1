# Build the desktop installer:
#   web build -> jar -> jlink runtime -> copy into shell -> electron-builder (NSIS)
# Usage: powershell -ExecutionPolicy Bypass -File scripts/build-desktop.ps1 [-SkipWeb]
param(
  [switch]$SkipWeb
)

$ErrorActionPreference = 'Stop'
$desktop = Split-Path -Parent $PSScriptRoot
$repo = Split-Path -Parent $desktop
$jdkHome = Join-Path $repo 'portable\jdk\jdk-17.0.20.1+1'
$mvn = Join-Path $repo 'portable\maven\apache-maven-3.9.16\bin\mvn.cmd'
$m2 = Join-Path $repo 'portable\m2'
$env:JAVA_HOME = $jdkHome

function Step($name) { Write-Host ""; Write-Host "=== $name ===" -ForegroundColor Cyan }

# 1) web
if (-not $SkipWeb) {
  Step 'Build web'
  Push-Location (Join-Path $repo 'web')
  try {
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw 'web build failed' }
  } finally { Pop-Location }
}

# 2) server jar
Step 'Package server'
Push-Location (Join-Path $repo 'server')
try {
  & $mvn -s mvn-settings.xml -DskipTests "-Dmaven.repo.local=$m2" clean package
  if ($LASTEXITCODE -ne 0) { throw 'server package failed' }
} finally { Pop-Location }

# 3) runtime
Step 'Build runtime'
& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-runtime.ps1')

# 4) resources
Step 'Prepare resources'
$res = Join-Path $desktop 'resources'
New-Item -ItemType Directory -Force -Path $res | Out-Null
Copy-Item (Join-Path $repo 'server\target\kb-server-0.1.0.jar') (Join-Path $res 'kb.jar') -Force
Write-Host 'kb.jar copied'

# 5) installer
Step 'Build installer'
Push-Location $desktop
try {
  & npx electron-builder --win nsis
  if ($LASTEXITCODE -ne 0) { throw 'electron-builder failed' }
} finally { Pop-Location }

Step 'Done'
Get-ChildItem (Join-Path $desktop 'dist') -Filter '*.exe' | ForEach-Object {
  Write-Host ("artifact: {0} ({1:N0} MB)" -f $_.FullName, ($_.Length / 1MB))
}
