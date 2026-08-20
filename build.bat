@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "JAVA_HOME_FOUND="

rem ---- 1. Respect an existing JAVA_HOME if it is a JDK 25+ ----
if defined JAVA_HOME call :testJdk "%JAVA_HOME%"
if defined JAVA_HOME_FOUND goto :run

rem ---- 2. Scan common install locations ----
for %%D in (
    "%ProgramFiles%\Java"
    "%ProgramFiles%\Eclipse Adoptium"
    "%ProgramFiles%\Microsoft"
    "%ProgramFiles%\Amazon Corretto"
    "%ProgramFiles%\Zulu"
    "%ProgramFiles%\GraalVM"
    "%ProgramFiles%\BellSoft"
    "%ProgramFiles%\Azul"
    "%ProgramFiles(x86)%\Java"
    "%LOCALAPPDATA%\Programs\Eclipse Adoptium"
    "%LOCALAPPDATA%\Programs\Microsoft"
    "%LOCALAPPDATA%\Programs\Amazon Corretto"
    "%LOCALAPPDATA%\Programs\Zulu"
) do (
    if exist "%%~D" (
        for /d %%J in ("%%~D\*") do (
            call :testJdk "%%~J"
            if defined JAVA_HOME_FOUND goto :run
        )
    )
)

rem ---- 3. Check the Windows registry ----
for /f "tokens=2*" %%a in ('reg query "HKLM\SOFTWARE\JavaSoft\JDK" /s 2^>nul ^| findstr /i "JavaHome"') do (
    call :testJdk "%%b"
    if defined JAVA_HOME_FOUND goto :run
)
for /f "tokens=2*" %%a in ('reg query "HKLM\SOFTWARE\WOW6432Node\JavaSoft\JDK" /s 2^>nul ^| findstr /i "JavaHome"') do (
    call :testJdk "%%b"
    if defined JAVA_HOME_FOUND goto :run
)

rem ---- 4. Fall back to java on PATH ----
where java >nul 2>nul
if not errorlevel 1 (
    for /f "delims=" %%J in ('where java 2^>nul') do (
        call :testJdk "%%~dpJ.."
        if defined JAVA_HOME_FOUND goto :run
    )
)

echo.
echo [ERROR] No JDK 25 or newer was found on this machine.
echo         Install one from https://adoptium.net/ and re-run.
echo         or https://www.oracle.com/ca-en/java/technologies/downloads/
echo.
pause
exit /b 1

:run
echo.
echo Found JDK: !JAVA_HOME_FOUND!
echo Building with gradlew.bat ...
echo.
set "JAVA_HOME=!JAVA_HOME_FOUND!"
set "PATH=!JAVA_HOME_FOUND!\bin;!PATH!"
call gradlew.bat build
set "BUILD_EXIT=%errorlevel%"
echo.
echo Build finished with exit code %BUILD_EXIT%.
pause
exit /b %BUILD_EXIT%

:testJdk
rem %~1 = candidate JDK home directory
if "%~1"=="" exit /b 0
if not exist "%~1\bin\javac.exe" exit /b 0
if not exist "%~1\bin\java.exe" exit /b 0

set "_out=%TEMP%\krs-jdk-check.txt"
"%~1\bin\java.exe" -XshowSettings:properties -version > "%_out%" 2>&1
if errorlevel 1 exit /b 0

set "_ver="
for /f "tokens=*" %%V in ('findstr /c:"java.specification.version" "%_out%"') do if not defined _ver set "_ver=%%V"
if not defined _ver exit /b 0

for /f "tokens=3" %%N in ("!_ver!") do set "_major=%%N"
for /f "tokens=1 delims=." %%M in ("!_major!") do set "_major=%%M"

if "!_major!"=="" exit /b 0
if !_major! LSS 25 exit /b 0

set "JAVA_HOME_FOUND=%~f1"
exit /b 0
