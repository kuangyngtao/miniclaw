@echo off
:: miniclaw launcher - build and run in one step
chcp 65001 >nul 2>&1

set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%miniclaw-cli\target\miniclaw-cli-0.1.0-SNAPSHOT.jar"
set "MVN=D:\JAVA\apache-maven-3.9.4\bin\mvn.cmd"

cd /d "%SCRIPT_DIR%"

if not exist "%JAR%" (
    if not exist "%MVN%" (
        echo [miniclaw] Maven not found at %MVN%
        echo Please update MVN path in miniclaw.bat or add Maven to PATH.
        exit /b 1
    )
    echo [miniclaw] building...
    call "%MVN%" install -pl miniclaw-cli -am -q -DskipTests
)

java -Dfile.encoding=UTF-8 -jar "%JAR%" %*
