# Build a trimmed Java runtime (jlink) into desktop/resources/jre
# Usage: powershell -ExecutionPolicy Bypass -File scripts/build-runtime.ps1
$ErrorActionPreference = 'Stop'

$desktop = Split-Path -Parent $PSScriptRoot
$repo = Split-Path -Parent $desktop
$jdk = Join-Path $repo 'portable\jdk\jdk-17.0.20.1+1'
$out = Join-Path $desktop 'resources\jre'

if (-not (Test-Path (Join-Path $jdk 'bin\jlink.exe'))) {
  throw "jlink not found: $jdk\bin\jlink.exe"
}
if (Test-Path $out) { Remove-Item $out -Recurse -Force }

Write-Host "jlink -> $out"
& (Join-Path $jdk 'bin\jlink.exe') `
  --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs,jdk.localedata `
  --strip-debug --no-header-files --no-man-pages --compress=2 `
  --output $out

if ($LASTEXITCODE -ne 0) { throw "jlink failed (exit=$LASTEXITCODE)" }

$size = (Get-ChildItem $out -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("runtime ready: {0:N0} MB" -f $size)
