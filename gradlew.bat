@echo off
setlocal
set GRADLE_VERSION=8.9
set BASE=%USERPROFILE%\.gradle\snapgpt-gradle
set DIST=%BASE%\gradle-%GRADLE_VERSION%
if exist "%DIST%\bin\gradle.bat" goto RUN
if not exist "%BASE%" mkdir "%BASE%"
set ZIP=%BASE%\gradle-%GRADLE_VERSION%-bin.zip
echo Downloading Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'; Expand-Archive -Path '%ZIP%' -DestinationPath '%BASE%' -Force"
if errorlevel 1 exit /b %errorlevel%
:RUN
call "%DIST%\bin\gradle.bat" %*
endlocal
