@echo off
setlocal
chcp 65001 >nul 2>&1
set "ROOT=%~dp0"
set "JAR=%ROOT%clawkit.jar"
if exist "%JAR%" goto run

set "JAR="
call :resolve_dev_jar
if defined JAR goto run

where mvn >nul 2>&1 || (echo [C-008] Maven was not found on PATH.& exit /b 3)
call mvn -B -ntp -pl clawkit-cli -am package -DskipTests || exit /b 3
call :resolve_dev_jar
if not defined JAR (
  echo [C-008] Built clawkit but could not locate the runnable JAR.
  exit /b 3
)

:run
java -Dfile.encoding=UTF-8 -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:resolve_dev_jar
for /f "delims=" %%F in ('dir /b /a-d /o-d "%ROOT%clawkit-cli\target\clawkit-cli-*.jar" 2^>nul ^| findstr /v /b /i "original-"') do (
  if not defined JAR set "JAR=%ROOT%clawkit-cli\target\%%F"
)
exit /b 0
