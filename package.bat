
@echo off
setlocal EnableExtensions
title FS Legal Size Scanner - Clean Package Build

cd /d "%~dp0"
if errorlevel 1 (
    echo ERROR: Could not enter the project directory.
    exit /b 1
)

set "APP_HOME=%~dp0"

REM ============================================================
REM Toolchain
REM ============================================================

set "JAVA_HOME=%APP_HOME%bin\jdk"
set "JAVAC=%JAVA_HOME%\bin\javac.exe"
set "JAVA=%JAVA_HOME%\bin\java.exe"
set "JAR=%JAVA_HOME%\bin\jar.exe"
set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"

REM ============================================================
REM Source and isolated packaging workspace
REM ============================================================

set "SRC=%APP_HOME%src"
set "BUILD=%APP_HOME%build"
set "PKG_BUILD=%BUILD%\package-build"
set "CLASSES=%PKG_BUILD%\classes"
set "SOURCE_LIST=%PKG_BUILD%\sources.txt"
set "STAGE=%PKG_BUILD%\input"

REM ============================================================
REM Distribution paths
REM ============================================================

set "DIST=%APP_HOME%dist"
set "PUBLISH_DIR=%DIST%\RatioJuris"
set "IMAGE_DIR=%PUBLISH_DIR%\FSScanner"

set "ICON=%APP_HOME%res\fs-scanner.ico"
set "PNG_ICON=%APP_HOME%res\fs-scanner.png"

echo.
echo ============================================================
echo   FS Legal Size Scanner - Clean Package Build
echo   Publisher: RatioJuris
echo ============================================================
echo.

REM ============================================================
REM [1/6] Validate required tools and source assets
REM ============================================================

echo [1/6] Checking build tools and required files...

if not exist "%JAVAC%" (
    echo ERROR: javac.exe was not found:
    echo "%JAVAC%"
    goto :fail
)

if not exist "%JAVA%" (
    echo ERROR: java.exe was not found:
    echo "%JAVA%"
    goto :fail
)

if not exist "%JAR%" (
    echo ERROR: jar.exe was not found:
    echo "%JAR%"
    goto :fail
)

if not exist "%JPACKAGE%" (
    echo ERROR: jpackage.exe was not found:
    echo "%JPACKAGE%"
    goto :fail
)

if not exist "%SRC%\" (
    echo ERROR: Java source directory is missing:
    echo "%SRC%"
    goto :fail
)

if not exist "%ICON%" (
    echo ERROR: ICO icon is missing:
    echo "%ICON%"
    goto :fail
)

if not exist "%PNG_ICON%" (
    echo ERROR: PNG icon is missing:
    echo "%PNG_ICON%"
    goto :fail
)

if not exist "%APP_HOME%bin\ScannerAPIGateway\ScannerAPIGateway.exe" (
    echo ERROR: ScannerAPIGateway.exe is missing.
    goto :fail
)

if not exist "%APP_HOME%bin\tesseract\tesseract.exe" (
    echo ERROR: tesseract.exe is missing.
    goto :fail
)

if not exist "%APP_HOME%bin\ImageMagick\magick.exe" (
    echo ERROR: ImageMagick magick.exe is missing.
    goto :fail
)

REM ============================================================
REM Clean only generated packaging output.
REM Preserve source files, run.bat, build\FSScanner.jar,
REM bundled tools, LICENSE.txt, app-details.xml, and MSI output.
REM ============================================================

echo.
echo Cleaning previous packaging output...

if exist "%PKG_BUILD%\" (
    rmdir /s /q "%PKG_BUILD%"
    if errorlevel 1 (
        echo ERROR: Could not clean the packaging workspace.
        echo Close any application using files in:
        echo "%PKG_BUILD%"
        goto :fail
    )
)

if exist "%PKG_BUILD%\" (
    echo ERROR: Packaging workspace still exists after cleanup.
    goto :fail
)

REM Remove only the generated FSScanner application image.
if exist "%IMAGE_DIR%\" (
    rmdir /s /q "%IMAGE_DIR%"
    if errorlevel 1 (
        echo ERROR: Could not remove the previous application image.
        echo Close FSScanner.exe if it is running.
        echo "%IMAGE_DIR%"
        goto :fail
    )
)

if exist "%IMAGE_DIR%\" (
    echo ERROR: Previous application image still exists.
    goto :fail
)

REM Recreate isolated build directories.
mkdir "%CLASSES%" 2>nul
if errorlevel 1 (
    echo ERROR: Could not create the classes directory.
    goto :fail
)

mkdir "%STAGE%" 2>nul
if errorlevel 1 (
    echo ERROR: Could not create the staging directory.
    goto :fail
)

REM ============================================================
REM Enumerate source files
REM ============================================================

dir /s /b "%SRC%\*.java" > "%SOURCE_LIST%" 2>nul

for %%F in ("%SOURCE_LIST%") do (
    if %%~zF EQU 0 (
        echo ERROR: No Java source files were found.
        goto :fail
    )
)

REM ============================================================
REM [2/6] Compile sources and create a fresh package JAR
REM ============================================================

echo.
echo [2/6] Compiling Java sources with bundled OpenJDK...

"%JAVAC%" -encoding UTF-8 -d "%CLASSES%" @"%SOURCE_LIST%"
if errorlevel 1 (
    echo ERROR: Java compilation failed.
    goto :fail
)

echo Creating fresh FSScanner.jar...

"%JAR%" --create ^
    --file "%STAGE%\FSScanner.jar" ^
    --main-class FSScanner ^
    -C "%CLASSES%" .

if errorlevel 1 (
    echo ERROR: Could not create FSScanner.jar.
    goto :fail
)

if not exist "%STAGE%\FSScanner.jar" (
    echo ERROR: Generated JAR is missing.
    goto :fail
)

REM ============================================================
REM [3/6] Stage native dependencies and resources
REM ============================================================

echo.
echo [3/6] Staging native dependencies...

mkdir "%STAGE%\bin" 2>nul
if errorlevel 1 (
    echo ERROR: Could not create native dependency directory.
    goto :fail
)

REM XCOPY return codes 0 and 1 are successful.
REM Codes 2 and above indicate an error.

xcopy "%APP_HOME%bin\ScannerAPIGateway" ^
    "%STAGE%\bin\ScannerAPIGateway\" /E /I /H /K /Y >nul

if errorlevel 2 (
    echo ERROR: Could not stage ScannerAPIGateway.
    goto :fail
)

xcopy "%APP_HOME%bin\tesseract" ^
    "%STAGE%\bin\tesseract\" /E /I /H /K /Y >nul

if errorlevel 2 (
    echo ERROR: Could not stage Tesseract.
    goto :fail
)

xcopy "%APP_HOME%bin\ImageMagick" ^
    "%STAGE%\bin\ImageMagick\" /E /I /H /K /Y >nul

if errorlevel 2 (
    echo ERROR: Could not stage ImageMagick.
    goto :fail
)

mkdir "%STAGE%\res" 2>nul
if errorlevel 1 (
    echo ERROR: Could not create the resource directory.
    goto :fail
)

copy /y "%PNG_ICON%" "%STAGE%\res\fs-scanner.png" >nul
if errorlevel 1 (
    echo ERROR: Could not stage fs-scanner.png.
    goto :fail
)

REM Confirm that critical staged files exist.
if not exist "%STAGE%\bin\ScannerAPIGateway\ScannerAPIGateway.exe" (
    echo ERROR: Staged scanner gateway is missing.
    goto :fail
)

if not exist "%STAGE%\bin\tesseract\tesseract.exe" (
    echo ERROR: Staged Tesseract executable is missing.
    goto :fail
)

if not exist "%STAGE%\bin\ImageMagick\magick.exe" (
    echo ERROR: Staged ImageMagick executable is missing.
    goto :fail
)

REM ============================================================
REM [4/6] Create the Windows application image
REM ============================================================

echo.
echo [4/6] Creating the Windows application image...

if not exist "%DIST%\" mkdir "%DIST%"
if errorlevel 1 (
    echo ERROR: Could not create distribution directory.
    goto :fail
)

if not exist "%PUBLISH_DIR%\" mkdir "%PUBLISH_DIR%"
if errorlevel 1 (
    echo ERROR: Could not create publisher directory.
    goto :fail
)

"%JPACKAGE%" ^
    --type app-image ^
    --name "FSScanner" ^
    --app-version "1.0.1" ^
    --vendor "RatioJuris" ^
    --description "Capture and assemble legal-size documents using flatbed scanners." ^
    --copyright "Copyright Isrgrajan Gonardiya" ^
    --input "%STAGE%" ^
    --main-jar "FSScanner.jar" ^
    --main-class "FSScanner" ^
    --icon "%ICON%" ^
    --dest "%PUBLISH_DIR%" ^
    --verbose

if errorlevel 1 (
    echo ERROR: jpackage failed to create the application image.
    goto :fail
)

REM ============================================================
REM [5/6] Validate application image and trimmed Java runtime
REM ============================================================

echo.
echo [5/6] Validating packaged files...

if not exist "%IMAGE_DIR%\FSScanner.exe" (
    echo ERROR: Application launcher is missing.
    goto :fail
)

if not exist "%IMAGE_DIR%\app\FSScanner.jar" (
    echo ERROR: Packaged application JAR is missing.
    goto :fail
)

if not exist "%IMAGE_DIR%\app\res\fs-scanner.png" (
    echo ERROR: Packaged PNG icon is missing.
    goto :fail
)

if not exist "%IMAGE_DIR%\app\bin\ScannerAPIGateway\ScannerAPIGateway.exe" (
    echo ERROR: Packaged scanner gateway is missing.
    goto :fail
)

if not exist "%IMAGE_DIR%\app\bin\tesseract\tesseract.exe" (
    echo ERROR: Packaged Tesseract executable is missing.
    goto :fail
)

if not exist "%IMAGE_DIR%\app\bin\ImageMagick\magick.exe" (
    echo ERROR: Packaged ImageMagick executable is missing.
    goto :fail
)

REM jlink --strip-native-commands removes java.exe.
REM Validate the runtime libraries used by the launcher instead.

if not exist "%IMAGE_DIR%\runtime\release" (
    echo ERROR: Bundled Java runtime metadata is missing.
    goto :fail
)

if not exist "%IMAGE_DIR%\runtime\bin\server\jvm.dll" (
    echo ERROR: Bundled JVM library is missing.
    goto :fail
)

if not exist "%IMAGE_DIR%\runtime\bin\java.dll" (
    echo ERROR: Bundled Java runtime library is missing.
    goto :fail
)

REM ============================================================
REM [6/6] Report completed build
REM ============================================================

echo.
echo [6/6] Packaging completed successfully.
echo.
echo ============================================================
echo   PACKAGE READY
echo ============================================================
echo.
echo Application directory:
echo "%IMAGE_DIR%"
echo.
echo Executable:
echo "%IMAGE_DIR%\FSScanner.exe"
echo.
echo The application image was cleaned and rebuilt.
echo The existing MSI output was preserved.
echo.
echo Next steps:
echo 1. Run FSScanner.exe as a standard user.
echo 2. Test scanning, OCR, image processing, and stitching.
echo 3. Run installer.bat to build the MSI separately.
echo 4. Build and validate the Microsoft Store package separately.
echo.
pause
exit /b 0

:fail
echo.
echo ============================================================
echo   PACKAGING FAILED
echo ============================================================
echo.
echo Review the error messages above.
echo Existing source files and run.bat were not intentionally modified.
echo The previous application image may have been removed before failure.
echo.
pause
exit /b 1
