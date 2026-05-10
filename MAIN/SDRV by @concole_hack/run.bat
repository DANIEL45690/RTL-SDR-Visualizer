@echo off
title RTL-SDR VISUALIZER - by @console_hack
color 0a

echo ================================================
echo    REAL SDR VISUALIZER v1.0
echo              by @console_hack
echo ================================================
echo.

cd /d "%~dp0"

if exist "rtl-sdr-x64\bin\rtl_tcp.exe" (
    echo [OK] RTL-SDR already installed
    goto :compile
)

echo [1/2] Downloading RTL-SDR from working mirror...
powershell -Command "Invoke-WebRequest -Uri 'https://github.com/harryjyoung/rtl-sdr-windows/releases/download/v1.0/rtl-sdr-windows-x64.zip' -OutFile 'rtl-sdr.zip' -UseBasicParsing"

if not exist "rtl-sdr.zip" (
    echo [ERROR] Download failed.
    echo.
    echo MANUAL INSTALL:
    echo 1. Download from: https://www.osmocom.org/rtl-sdr/
    echo 2. Or install via chocolatey: choco install rtl-sdr
    echo 3. Or install via winget: winget install rtl-sdr
    echo.
    pause
    exit /b 1
)

echo [2/2] Extracting...
powershell -Command "Expand-Archive -Path rtl-sdr.zip -DestinationPath rtl-sdr-x64 -Force"
del rtl-sdr.zip

:compile
if not exist "rtl-sdr-x64\bin\rtl_tcp.exe" (
    echo [ERROR] rtl_tcp.exe not found in extracted files
    echo Check folder structure and try again
    pause
    exit /b 1
)

echo.
echo [OK] Starting RTL-TCP...
start "RTL-TCP" cmd /c "cd /d "%~dp0rtl-sdr-x64\bin" && rtl_tcp.exe -a 127.0.0.1 -p 1234"

timeout /t 3 /nobreak >nul

echo.
echo ================================================
echo    Starting visualizer...
echo    NOTE: This requires physical RTL-SDR USB dongle
echo    Without hardware - will show connection error
echo ================================================
echo.

if not exist "RealSDRVisualizer.class" (
    echo Compiling Java code...
    javac RealSDRVisualizer.java
)

java RealSDRVisualizer

pause
