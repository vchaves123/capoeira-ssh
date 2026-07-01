@echo off
setlocal enabledelayedexpansion

for %%f in (target\14bis-ssh-*.jar) do set JAR=%%~nxf
set REQUIRED_JAVA=21
set OK=1

:: ── Check Java ───────────────────────────────────────────────
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found in PATH.
    echo         Download Java 21 from https://adoptium.net and re-run this script.
    set OK=0
) else (
    for /f "tokens=3 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%~v
    for /f "delims=." %%m in ("!JAVA_VER!") do set JAVA_MAJOR=%%m
    if !JAVA_MAJOR! LSS %REQUIRED_JAVA% (
        echo [ERROR] Java !JAVA_MAJOR! found, but Java %REQUIRED_JAVA%+ is required.
        echo         Download Java 21 from https://adoptium.net and re-run this script.
        set OK=0
    )
)

:: ── Check Maven ───────────────────────────────────────────────
if !OK!==1 (
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Maven not found in PATH ^(required to build the project^).
        echo         Download Maven from https://maven.apache.org/download.cgi,
        echo         add its bin folder to PATH, and re-run this script.
        set OK=0
    )
)

if !OK!==0 ( pause & exit /b 1 )

:: ── Build ─────────────────────────────────────────────────────
call mvn clean package -DskipTests
if errorlevel 1 ( echo [ERROR] Build failed. & pause & exit /b 1 )

:: ── Run ──────────────────────────────────────────────────────
if not defined JAR ( echo [ERROR] No JAR found in target\. & pause & exit /b 1 )
java -jar "target\%JAR%"
