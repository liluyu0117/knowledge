@echo off
rem 知识库一键启动：双击后等十几秒，浏览器自动打开 http://localhost:39217
title 知识库服务(关闭本窗口即停止)
set "JAVA_HOME=E:\tool\jdk-17.0.15"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0server"
start "" powershell -WindowStyle Hidden -Command "Start-Sleep 12; Start-Process http://localhost:39217"
call mvn -s mvn-settings.xml spring-boot:run
echo.
echo 服务已退出。
pause
