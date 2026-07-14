@echo off
echo ===================================================
echo  NoSQL Distributed Database Compiler and Packager
echo ===================================================

:: 1. Clean and create temp bin and dist directory
if exist bin rmdir /s /q bin
mkdir bin
if not exist dist mkdir dist

:: 2. Gather all Java source files using relative paths via Python to bypass single quote bug
python -c "import os; open('sources.txt', 'w', encoding='utf-8').write('\n'.join([os.path.join(r, f) for r, d, fs in os.walk('src') for f in fs if f.endswith('.java')]))"

echo [1/3] Compiling Java source files...
javac -encoding UTF-8 -d bin @sources.txt
if %errorlevel% neq 0 (
    echo [ERROR] Compilation failed. Please check your JDK setup and source code.
    del sources.txt
    pause
    exit /b %errorlevel%
)
del sources.txt
echo [SUCCESS] Compilation completed.

:: 3. Package into executable JAR files in dist directory
echo [2/3] Packaging JAR executable files...

jar cfe dist\server.jar nosql.server.DatabaseServer -C bin .
jar cfe dist\cli.jar nosql.client.NoSqlCli -C bin .
jar cfe dist\gui.jar nosql.client.NoSqlGui -C bin .
jar cfe dist\benchmark.jar nosql.benchmark.BenchmarkTool -C bin .
jar cfe dist\shell.jar nosql.client.ShellClient -C bin .

echo [SUCCESS] JAR packages generated in dist/ directory:
echo   - dist/server.jar (Database Server)
echo   - dist/cli.jar (Interactive CLI Client)
echo   - dist/gui.jar (Swing GUI Client)
echo   - dist/benchmark.jar (Stress Benchmark Tool)
echo   - dist/shell.jar (Shell Command Tool)

:: 4. Clean up temp files
rmdir /s /q bin
echo [3/3] Cleanup complete. Build SUCCESS.
echo ===================================================
pause
