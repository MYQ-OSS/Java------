@echo off
echo ===================================================
echo  NoSQL Redis Desktop Manager Launcher
echo ===================================================
:: Check if gui.jar exists in dist
if not exist dist\gui.jar (
    echo [ERROR] dist\gui.jar not found. Please run build.bat first to compile the project.
    pause
    exit /b 1
)

echo Launching NoSQL Redis Desktop Manager GUI Client...
start "" java -jar dist\gui.jar
echo [SUCCESS] GUI Client has been launched.
echo ===================================================
