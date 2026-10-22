@echo off
setlocal EnableExtensions
title FS Legal Size Scanner - MSI Installer

cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JPACKAGE=%APP_HOME%bin\jdk\bin\jpackage.exe"
set "IMAGE_DIR=%APP_HOME%dist\RatioJuris\FSScanner"
set "DIST=%APP_HOME%dist\msi"
set "ICON=%APP_HOME%res\fs-scanner.ico"
set "EULA=%APP_HOME%EULA.txt"

echo.
echo ============================================================
echo   FS Legal Size Scanner - MSI Installer
echo   Publisher: RatioJuris
echo ============================================================
echo.

if not exist "%JPACKAGE%" (
    echo ERROR: jpackage.exe was not found.
    goto :fail
)

if not exist "%IMAGE_DIR%\FSScanner.exe" (
    echo ERROR: Application image was not found.
    echo Run package.bat first.
    goto :fail
)

if not exist "%ICON%" (
    echo ERROR: Application icon was not found:
    echo "%ICON%"
    goto :fail
)

if not exist "%EULA%" (
    echo ERROR: EULA.txt was not found:
    echo "%EULA%"
    goto :fail
)

if not exist "%DIST%" mkdir "%DIST%"

echo Creating the MSI installer...
echo.

"%JPACKAGE%" ^
    --type msi ^
    --name "FSScanner" ^
    --app-version "1.0.1" ^
    --vendor "RatioJuris" ^
    --description "Capture and assemble legal-size documents using flatbed scanners." ^
    --copyright "Copyright Isrgrajan Gonardiya" ^
    --app-image "%IMAGE_DIR%" ^
    --icon "%ICON%" ^
    --license-file "%EULA%" ^
    --dest "%DIST%" ^
    --install-dir "RatioJuris\FSScanner" ^
    --win-menu ^
    --win-menu-group "RatioJuris" ^
    --win-shortcut ^
    --win-dir-chooser ^
    --verbose

if errorlevel 1 (
    echo.
    echo ERROR: MSI generation failed.
    goto :fail
)

if not exist "%DIST%\FSScanner-1.0.1.msi" (
    echo ERROR: MSI output was not found.
    goto :fail
)

echo.
echo ============================================================
echo   MSI CREATED SUCCESSFULLY
echo ============================================================
echo.
echo Installer:
echo "%DIST%\FSScanner-1.0.1.msi"
echo.
pause
exit /b 0

:fail
echo.
echo MSI generation failed.
echo The existing application image was not intentionally modified.
echo.
pause
exit /b 1