@echo off
rem ============================================
rem  知识库一键启动（统一入口）
rem  实际逻辑在 portable\start.bat：自动准备 JDK / Maven / 构建 jar 后启动
rem ============================================
cd /d "%~dp0"
if not exist "portable\start.bat" (
    echo [错误] 未找到 portable\start.bat，请确认项目目录完整
    pause
    exit /b 1
)
call "portable\start.bat"
