@echo off
setlocal
chcp 65001 >nul 2>&1
set "ROOT=%~dp0"
set "JAR=%ROOT%clawkit-cli\target\clawkit-cli-0.1.0-SNAPSHOT.jar"
if not exist "%JAR%" (
  where mvn >nul 2>&1 || (echo [C-008] Maven was not found on PATH.& exit /b 3)
  call mvn -B -ntp -pl clawkit-cli -am package -DskipTests || exit /b 3
)
java -Dfile.encoding=UTF-8 -jar "%JAR%" %*
