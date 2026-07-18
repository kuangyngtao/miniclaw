@echo off
:: Backward-compatible launcher. The product is now named clawkit.
chcp 65001 >nul 2>&1

set "SCRIPT_DIR=%~dp0"
echo [deprecated] Use clawkit.cmd instead.
call "%SCRIPT_DIR%clawkit.cmd" %*
