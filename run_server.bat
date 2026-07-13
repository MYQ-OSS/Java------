@echo off
echo ===================================================
echo  NoSQL Redis-Compatible Database Server Launcher
echo ===================================================
:: Check if server.jar exists
if not exist dist\server.jar (
    echo [ERROR] dist\server.jar not found. Please run build.bat first to compile the project.
    pause
    exit /b 1
)

echo Starting NoSQL Database Server in STANDALONE mode...
echo Port     : 8080
echo Data Dir : ./data
echo ===================================================
java -jar dist\server.jar --port 8080 --mode standalone --data-dir ./data
pause
