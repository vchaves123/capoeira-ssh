@echo off
setlocal enabledelayedexpansion

rem Launches 14bis SSH using a Java runtime already installed on this machine
rem (this bundle ships no JRE). Requires Java 21 or newer.

set SCRIPT_DIR=%~dp0
set LIB_DIR=%SCRIPT_DIR%lib

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found in PATH.
    echo         Install Java 21+ ^(e.g. https://adoptium.net^) and re-run this script.
    pause
    exit /b 1
)

for /f "tokens=3 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%~v
set JAVA_VER=%JAVA_VER:"=%
for /f "delims=." %%m in ("%JAVA_VER%") do set JAVA_MAJOR=%%m
if %JAVA_MAJOR% LSS 21 (
    echo [ERROR] Java %JAVA_MAJOR% found, but Java 21+ is required.
    echo         Install Java 21+ ^(e.g. https://adoptium.net^) and re-run this script.
    pause
    exit /b 1
)

set ARCH=x86_64
if /I "%PROCESSOR_ARCHITECTURE%"=="ARM64" set ARCH=aarch64
if /I "%PROCESSOR_ARCHITEW6432%"=="ARM64" set ARCH=aarch64

set SWT_JAR=%LIB_DIR%\swt\org.eclipse.swt.win32.win32.%ARCH%.jar
if not exist "%SWT_JAR%" (
    echo [ERROR] No SWT build for Windows/%ARCH% in lib\swt\.
    pause
    exit /b 1
)

rem javaw has no console window of its own, so launching it lets this cmd.exe window
rem close immediately instead of staying open (and blank) for the app's whole lifetime.
set LAUNCHER=java
where javaw >nul 2>&1
if not errorlevel 1 set LAUNCHER=javaw

start "" "%LAUNCHER%" -cp "%LIB_DIR%\*;%SWT_JAR%" br.com.quatorzebis.ssh.Main
