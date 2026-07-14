@echo off
title NoSQL Redis-Compatible Interactive Shell
echo ===================================================
echo  NoSQL Database Interactive Shell (Redis-Cli Mode)
echo ===================================================
echo.

:: Check if cli.jar exists
if not exist dist\cli.jar (
    echo [ERROR] dist\cli.jar not found. Please run build.bat first to compile the project.
    pause
    exit /b 1
)

:: Default connection settings
set HOST=127.0.0.1
set PORT=8080

:: Parse command line arguments
:parse
if "%~1"=="" goto :launch
if /I "%~1"=="--host" (
    set HOST=%~2
    shift
    shift
    goto :parse
)
if /I "%~1"=="--port" (
    set PORT=%~2
    shift
    shift
    goto :parse
)
if /I "%~1"=="-h" (
    set HOST=%~2
    shift
    shift
    goto :parse
)
if /I "%~1"=="-p" (
    set PORT=%~2
    shift
    shift
    goto :parse
)
shift
goto :parse

:launch
echo Connecting to %HOST%:%PORT%...
echo Type your Redis commands below (e.g. SET, GET, LPUSH, MSET, MGET)
echo Type 'exit' or 'quit' to leave the shell.
echo Special commands:
echo   CREATE COLLECTION ^<name^>    - Create a new collection
echo   DROP COLLECTION ^<name^>      - Delete a collection
echo   LIST COLLECTIONS              - Show all collections
echo   MSET k1 v1 k2 v2 ...          - Batch set
echo   MGET k1 k2 ...                - Batch get
echo ===================================================
echo.

java -jar dist\cli.jar --host %HOST% --port %PORT%

echo.
echo Shell session ended.
pause
