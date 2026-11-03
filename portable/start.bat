@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
title 知识库一键启动

rem ---------------------------------------------------------------
rem  知识库一键启动（Windows）
rem  1. 停掉占用端口的旧服务
rem  2. 准备 JDK（缺失则自动下载）
rem  3. 需要时构建 jar（会检查 jar 内是否包含前端页面）
rem  4. 启动服务并打开浏览器
rem ---------------------------------------------------------------

set "PORT=39217"

rem ---- 启动前自清理：端口被占用则先停掉旧服务 ----
set "OLD_PID="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":%PORT%" ^| findstr "LISTENING"') do set "OLD_PID=%%P"
if not defined OLD_PID goto :jdk
echo [0/3] 端口 %PORT% 已被进程 !OLD_PID! 占用，先停止旧服务...
tasklist /fi "PID eq !OLD_PID!" 2>nul | findstr /i "java.exe" >nul
if errorlevel 1 (
    echo [错误] 端口 %PORT% 被非 Java 进程占用，请手动关闭后重试
    pause
    exit /b 1
)
taskkill /PID !OLD_PID! /F >nul 2>&1
ping -n 3 127.0.0.1 >nul
echo 旧服务已停止

:jdk
rem ---- 查找 JDK：优先用本目录 jdk ----
set "JAVA_CMD="
if exist "jdk\bin\java.exe" set "JAVA_CMD=%cd%\jdk\bin\java.exe"
for /d %%D in ("jdk\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_CMD=%%~fD\bin\java.exe"
if defined JAVA_CMD goto :jdk_ok
where java >nul 2>nul && set "JAVA_CMD=java" && goto :jdk_ok
echo [1/3] 未检测到 JDK 17，自动下载（约 190MB，只需一次，下载到本目录 jdk）...
powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol='Tls12'; $urls=@('https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip','https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'); foreach($u in $urls){ try { Invoke-WebRequest -Uri $u -OutFile jdk.zip -TimeoutSec 600; break } catch { Write-Host '  源不可用，换下一个' } }; if (Test-Path jdk.zip) { Expand-Archive jdk.zip -DestinationPath jdk -Force; Remove-Item jdk.zip }"
for /d %%D in ("jdk\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_CMD=%%~fD\bin\java.exe"
if not defined JAVA_CMD (
    echo [错误] JDK 下载失败，请手动安装 JDK 17 后重试
    pause
    exit /b 1
)
:jdk_ok
echo 已找到 Java: !JAVA_CMD!

rem ---- 是否需要构建：jar 不存在，或 jar 内没有前端页面 ----
if not exist "kb-server.jar" goto :need_build
set "HAS_STATIC=1"
rem 必须用 Windows 自带 tar（bsdtar），Git 自带 tar 读不了 jar
set "TAR_EXE=%SystemRoot%\System32\tar.exe"
if not exist "%TAR_EXE%" set "TAR_EXE=tar"
"%TAR_EXE%" -tf kb-server.jar > kbjarlist.txt 2>nul
if errorlevel 1 goto :check_static_done
findstr /i /c:"BOOT-INF/classes/static/index.html" kbjarlist.txt >nul 2>&1
if not errorlevel 1 goto :check_static_done
set "HAS_STATIC=0"
:check_static_done
del kbjarlist.txt >nul 2>&1
if "%HAS_STATIC%"=="1" goto :run
if exist "..\server\src\main\resources\static\index.html" (
    echo [提示] 现有 jar 内没有前端页面，将重新构建（含前端）
    goto :need_build
)
echo [警告] jar 内没有前端页面，且前端产物不存在，页面将无法访问
echo         请先到 web 目录执行 npm run build，再删除 kb-server.jar 重试
goto :run

:need_build
if not exist "..\server\src\main\resources\static\index.html" (
    if exist "..\web\node_modules" (
        echo 前端尚未构建，正在构建前端（约 30 秒）...
        pushd ..\web
        call npm run build
        popd
    )
)
echo [2/3] 准备构建服务 jar（首次约 3-5 分钟，之后无需再构建）...

set "MVN_CMD="
if exist "maven\bin\mvn.cmd" set "MVN_CMD=%cd%\maven\bin\mvn.cmd"
for /d %%D in ("maven\apache-maven-*") do if exist "%%D\bin\mvn.cmd" set "MVN_CMD=%%~fD\bin\mvn.cmd"
if defined MVN_CMD goto :mvn_ok
echo 下载 Maven（清华镜像，失败换官方源）...
powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol='Tls12'; $urls=@('https://mirrors.tuna.tsinghua.edu.cn/apache/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip','https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip'); foreach($u in $urls){ try { Invoke-WebRequest -Uri $u -OutFile maven.zip -TimeoutSec 300; break } catch { Write-Host '  源不可用，换下一个' } }; if (Test-Path maven.zip) { Expand-Archive maven.zip -DestinationPath maven -Force; Remove-Item maven.zip }"
for /d %%D in ("maven\apache-maven-*") do if exist "%%D\bin\mvn.cmd" set "MVN_CMD=%%~fD\bin\mvn.cmd"
if not defined MVN_CMD (
    echo [错误] Maven 下载失败
    pause
    exit /b 1
)
:mvn_ok
if exist "jdk\bin\java.exe" set "JAVA_HOME=%cd%\jdk"
for /d %%D in ("jdk\jdk-*") do set "JAVA_HOME=%%~fD"

echo 构建中...
pushd ..\server
call "!MVN_CMD!" -s mvn-settings.xml -q -DskipTests -Dmaven.repo.local=%~dp0m2 package
if errorlevel 1 (
    popd
    echo [错误] 构建失败，请检查网络后重试
    pause
    exit /b 1
)
popd
copy /y "..\server\target\kb-server-0.1.0.jar" "kb-server.jar" >nul
if not exist "kb-server.jar" (
    echo [错误] 未找到构建产物 jar
    pause
    exit /b 1
)

:run
echo [3/3] 启动知识库服务：http://localhost:%PORT%（Ctrl+C 停止）
start "" powershell -NoProfile -Command "Start-Sleep 3; Start-Process 'http://localhost:%PORT%'"
if not exist tmp mkdir tmp
"%JAVA_CMD%" -Djava.io.tmpdir="%cd%\tmp" -jar kb-server.jar --server.port=%PORT%
pause
