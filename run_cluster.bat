@echo off
echo ===================================================
echo  NoSQL 3-Node High-Availability Cluster Launcher
echo ===================================================

:: Check if server.jar exists in dist
if not exist dist\server.jar (
    echo [ERROR] dist\server.jar not found. Please run build.bat first to compile the project.
    pause
    exit /b 1
)

echo Launching Node 1 (Port: 8081, ID: node_1)...
start "NoSQL Server [Node 1 - Port 8081]" java -jar dist\server.jar --port 8081 --mode cluster --id node_1 --peers node_1=127.0.0.1:8081,node_2=127.0.0.1:8082,node_3=127.0.0.1:8083 --data-dir ./data/n1

echo Launching Node 2 (Port: 8082, ID: node_2)...
start "NoSQL Server [Node 2 - Port 8082]" java -jar dist\server.jar --port 8082 --mode cluster --id node_2 --peers node_1=127.0.0.1:8081,node_2=127.0.0.1:8082,node_3=127.0.0.1:8083 --data-dir ./data/n2

echo Launching Node 3 (Port: 8083, ID: node_3)...
start "NoSQL Server [Node 3 - Port 8083]" java -jar dist\server.jar --port 8083 --mode cluster --id node_3 --peers node_1=127.0.0.1:8081,node_2=127.0.0.1:8082,node_3=127.0.0.1:8083 --data-dir ./data/n3

echo.
echo [SUCCESS] All 3 cluster nodes have been launched in separate terminal windows.
echo - Client can connect to any of the nodes (e.g. 127.0.0.1:8081).
echo - System will automatically initiate Leader election.
echo ===================================================
pause
