@echo off
REM ============================================================
REM  easy-db Shell Tool 包装脚本 (Windows)
REM  用法: easy-db <command> [args...]
REM  示例: easy-db set name 张三
REM ============================================================

setlocal

REM 查找 jar 包位置（与脚本同目录的 dist/shell.jar）
set SCRIPT_DIR=%~dp0
set JAR_PATH=%SCRIPT_DIR%dist\shell.jar

if not exist "%JAR_PATH%" (
    echo [easy-db] shell.jar not found, building...
    call "%SCRIPT_DIR%build.bat"
    if not exist "%JAR_PATH%" (
        echo [easy-db] Build failed or shell.jar still not found.
        exit /b 1
    )
)

java -jar "%JAR_PATH%" %*
exit /b %errorlevel%
