@echo off
REM ============================================================
REM  QR Attendance System — Windows Launcher
REM  Usage: double-click run.bat  OR  run.bat [command]
REM
REM  Commands:
REM    run.bat           -> Build and start the server
REM    run.bat start     -> Start server (skip tests)
REM    run.bat test      -> Run all tests
REM    run.bat build     -> Compile only (no run, no tests)
REM    run.bat clean     -> Wipe target/ and data/ folders
REM ============================================================

setlocal

set CMD=%1
if "%CMD%"=="" set CMD=start

echo.
echo  =============================================
echo   QR Attendance System — Backend
echo   Running: %CMD%
echo  =============================================
echo.

REM Check Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    REM Fallback: Try to find VS Code RedHat Java Extension
    for /d %%D in ("%USERPROFILE%\.vscode\extensions\redhat.java-*") do (
        for /d %%J in ("%%D\jre\*") do (
            if exist "%%J\bin\java.exe" (
                set "JAVA_HOME=%%J"
                set "PATH=%%J\bin;%PATH%"
            )
        )
    )
)

REM Check again after possible fallback
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo  ERROR: Java not found.
    echo  Please install Java 21 from https://adoptium.net/
    echo  and make sure 'java' is on your PATH.
    pause
    exit /b 1
)

REM Determine Maven command (wrapper preferred, fallback to system mvn)
if exist mvnw.cmd (
    set MVN=mvnw.cmd
) else (
    mvn -version >nul 2>&1
    if %errorlevel% neq 0 (
        echo  ERROR: Maven not found and mvnw.cmd not present.
        echo  Please install Maven from https://maven.apache.org/download.cgi
        pause
        exit /b 1
    )
    set MVN=mvn
)

REM ── Commands ──────────────────────────────────────────────

if "%CMD%"=="start" (
    echo  Starting server on http://localhost:8080
    echo  Press Ctrl+C to stop.
    echo.
    call %MVN% spring-boot:run -DskipTests --no-transfer-progress
    goto :end
)

if "%CMD%"=="run" (
    echo  Starting server on http://localhost:8080
    echo  Press Ctrl+C to stop.
    echo.
    call %MVN% spring-boot:run -DskipTests --no-transfer-progress
    goto :end
)

if "%CMD%"=="test" (
    echo  Running all 322 tests...
    echo.
    call %MVN% clean test --no-transfer-progress
    goto :end
)

if "%CMD%"=="build" (
    echo  Compiling project...
    call %MVN% clean compile -DskipTests --no-transfer-progress
    goto :end
)

if "%CMD%"=="clean" (
    echo  Cleaning build artifacts and database files...
    call %MVN% clean --no-transfer-progress
    if exist data (
        rmdir /s /q data
        echo  Deleted data/ (H2 database files)
    )
    echo  Clean complete.
    goto :end
)

echo  Unknown command: %CMD%
echo  Valid commands: start, test, build, clean
exit /b 1

:end
endlocal
