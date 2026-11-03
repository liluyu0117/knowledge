@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
title 知识库一键启动

REM ══════════════════════════════════════════════════════════
REM  知识库一键启动（Windows / cmd / PowerShell 双击均可）
REM  首次运行会自动：下载 JDK 17 → 下载 Maven → 构建服务 jar
REM  之后每次运行直接启动，数据目录为本目录下的 .kb\
REM ══════════════════════════════════════════════════════════

set "JAVA_CMD="
if exist "jdk\bin\java.exe" set "JAVA_CMD=jdk\bin\java.exe"
for /d %%D in ("jdk\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_CMD=%%D\bin\java.exe"
if not defined JAVA_CMD (
    where java >nul 2>nul && set "JAVA_CMD=java"
)

if not defined JAVA_CMD (
    echo [1/3] 未检测到 JDK 17，自动下载（约 190MB，只需一次，下载到本目录 jdk\）...
    powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol='Tls12'; $urls=@('https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip','https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'); foreach($u in $urls){ try { Invoke-WebRequest -Uri $u -OutFile jdk.zip -TimeoutSec 600; break } catch { Write-Host \"  源不可用，换下一个\" } }; if (Test-Path jdk.zip) { Expand-Archive jdk.zip -DestinationPath jdk -Force; Remove-Item jdk.zip }"
    for /d %%D in ("jdk\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_CMD=%%D\bin\java.exe"
)
if not defined JAVA_CMD (
    echo [错误] JDK 下载/安装失败，请手动安装 JDK 17 后重试
    pause & exit /b 1
)
echo 已找到 Java: !JAVA_CMD!

if exist "kb-server.jar" goto :run

echo [2/3] 未找到 kb-server.jar，准备构建（首次约 3-5 分钟，之后无需再构建）...
set "MVN_CMD="
if exist "maven\bin\mvn.cmd" set "MVN_CMD=maven\bin\mvn.cmd"
for /d %%D in ("maven\apache-maven-*") do if exist "%%D\bin\mvn.cmd" set "MVN_CMD=%%D\bin\mvn.cmd"
if not defined MVN_CMD (
    echo 下载 Maven（清华镜像，失败换官方源）...
    powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol='Tls12'; $urls=@('https://mirrors.tuna.tsinghua.edu.cn/apache/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip','https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip'); foreach($u in $urls){ try { Invoke-WebRequest -Uri $u -OutFile maven.zip -TimeoutSec 300; break } catch { Write-Host \"  源不可用，换下一个\" } }; if (Test-Path maven.zip) { Expand-Archive maven.zip -DestinationPath maven -Force; Remove-Item maven.zip }"
    for /d %%D in ("maven\apache-maven-*") do if exist "%%D\bin\mvn.cmd" set "MVN_CMD=%%D\bin\mvn.cmd"
)
if not defined MVN_CMD (
    echo [错误] Maven 下载失败
    pause & exit /b 1
)

REM 强制用包内 JDK 构建：系统 JAVA_HOME 可能指向过旧的 Java
if exist "jdk\bin\java.exe" set "JAVA_HOME=%cd%\jdk"
for /d %%D in ("jdk\jdk-*") do set "JAVA_HOME=%%~fD"

echo 构建中...
pushd ..
call "!MVN_CMD!" -s mvn-settings.xml -q -DskipTests -Dmaven.repo.local="%cd%\portable\m2" package
if errorlevel 1 (
    popd
    echo [错误] 构建失败，请检查网络后重试
    pause & exit /b 1
)
popd
copy /y "..\server\target\kb-server-0.1.0.jar" "kb-server.jar" >nul
if not exist "kb-server.jar" (
    echo [错误] 未找到构建产物 jar
    pause & exit /b 1
)

:run
echo [3/3] 启动知识库服务：http://localhost:8080（Ctrl+C 停止）
start "" powershell -NoProfile -Command "Start-Sleep 3; Start-Process 'http://localhost:8080'"
if not exist tmp mkdir tmp
"%JAVA_CMD%" -Djava.io.tmpdir="%cd%	mp" -jar kb-server.jar --server.port=8080
pause
