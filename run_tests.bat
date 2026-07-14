@echo off
setlocal enabledelayedexpansion
echo ===================================================
echo  NoSQL Database Unit Test Runner (JUnit 5)
echo ===================================================
echo.

:: Check for JUnit jar
if not exist lib\junit-platform-console-standalone-1.11.4.jar (
    echo [ERROR] JUnit 5 jar not found in lib/
    echo Please download it first:
    echo   curl -L -o lib\junit-platform-console-standalone-1.11.4.jar https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar
    pause
    exit /b 1
)

:: Clean and create temp directories
if exist test-bin rmdir /s /q test-bin
mkdir test-bin

:: 1. Compile main source if classes don't exist
echo [1/3] Gathering source files...
python -c "import os; open('sources_test.txt', 'w', encoding='utf-8').write('\n'.join([os.path.join(r, f) for r, d, fs in os.walk('src') for f in fs if f.endswith('.java')]))"
python -c "import os; open('test_files.txt', 'w', encoding='utf-8').write('\n'.join([os.path.join(r, f) for r, d, fs in os.walk('test') for f in fs if f.endswith('.java')]))"

echo [2/3] Compiling test sources...
javac -encoding UTF-8 -d test-bin -cp "lib\junit-platform-console-standalone-1.11.4.jar;src" @sources_test.txt @test_files.txt
if %errorlevel% neq 0 (
    echo [ERROR] Test compilation failed. See above for details.
    del sources_test.txt test_files.txt
    pause
    exit /b %errorlevel%
)
del sources_test.txt test_files.txt
echo [SUCCESS] Test compilation completed.

:: 3. Run tests
echo.
echo [3/3] Running JUnit 5 tests...
echo ===================================================
java -jar lib\junit-platform-console-standalone-1.11.4.jar ^
    --class-path test-bin ^
    --scan-class-path ^
    --details tree ^
    --disable-banner

set TEST_RESULT=%errorlevel%
echo ===================================================

:: Cleanup
rmdir /s /q test-bin

if %TEST_RESULT% equ 0 (
    echo [SUCCESS] All tests passed!
) else (
    echo [FAILURE] Some tests failed. Check output above.
)
pause
exit /b %TEST_RESULT%
