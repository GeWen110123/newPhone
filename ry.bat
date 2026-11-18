@echo off
setlocal
set "JAR=%~dp0target\phone.jar"

if not exist "%JAR%" (
    echo Build the project with mvn clean package before starting. 1>&2
    exit /b 1
)

set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"

"%JAVA%" -Duser.timezone=Asia/Shanghai -jar "%JAR%" %*
exit /b %ERRORLEVEL%
