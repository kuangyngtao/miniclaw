@echo off
setlocal enabledelayedexpansion

:: Find Chrome
set "CHROME="
if exist "%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe" (
    set "CHROME=%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"
)
if "%CHROME%"=="" (
    if exist "C:\Program Files\Google\Chrome\Application\chrome.exe" (
        set "CHROME=C:\Program Files\Google\Chrome\Application\chrome.exe"
    )
)
if "%CHROME%"=="" (
    if exist "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe" (
        set "CHROME=C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"
    )
)
if "%CHROME%"=="" (
    echo Chrome not found.
    pause
    exit /b 1
)

echo [chrome-debug] Found Chrome: !CHROME!

:: Kill existing Chrome
echo [chrome-debug] Closing existing Chrome...
taskkill /F /IM chrome.exe >nul 2>&1
timeout /t 2 /nobreak >nul

:: Launch with debug port ONLY - no extra anti-detection flags that might trigger anti-bot
echo [chrome-debug] Launching Chrome with debug port 9222 (no extra flags)...
start "" "!CHROME!" --remote-debugging-port=9222

echo.
echo [chrome-debug] Done. Chrome running with debug port 9222.
echo Try opening zhipin.com manually first to test.
echo.
pause
