@echo off
setlocal EnableExtensions

set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="

cd /d "%~dp0"

title FS Scanner 365 Premiere - Build and Run

set "APP_HOME=%~dp0"
set "JAVA_HOME=%APP_HOME%bin\jdk"

set "JAVAC=%JAVA_HOME%\bin\javac.exe"
set "JAVA=%JAVA_HOME%\bin\java.exe"
set "JAR=%JAVA_HOME%\bin\jar.exe"

set "SRC=%APP_HOME%src"
set "BUILD=%APP_HOME%build"
set "CLASSES=%BUILD%\classes"
set "JAR_FILE=%BUILD%\FSScanner.jar"

echo.
echo ==================================================
echo         FS Scanner 365 Premiere
echo         Build and Run
echo         RatioJuris
echo ==================================================
echo.

REM --------------------------------------------------
REM Check bundled JDK
REM --------------------------------------------------

if not exist "%JAVAC%" (
    echo ERROR: javac.exe not found.
    echo.
    echo Expected:
    echo %JAVAC%
    echo.
    pause
    exit /b 1
)

if not exist "%JAVA%" (
    echo ERROR: java.exe not found.
    echo.
    echo Expected:
    echo %JAVA%
    echo.
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo ERROR: jar.exe not found.
    echo.
    echo Expected:
    echo %JAR%
    echo.
    pause
    exit /b 1
)

REM --------------------------------------------------
REM Check source directory
REM --------------------------------------------------

if not exist "%SRC%" (
    echo ERROR: src directory not found.
    echo.
    echo Expected:
    echo %SRC%
    echo.
    pause
    exit /b 1
)

REM --------------------------------------------------
REM Clean previous build
REM --------------------------------------------------

echo [1/3] Cleaning previous build...
echo.

if exist "%CLASSES%" (
    rmdir /s /q "%CLASSES%"
)

if exist "%JAR_FILE%" (
    del /f /q "%JAR_FILE%"
)

if not exist "%BUILD%" (
    mkdir "%BUILD%"
)

if not exist "%CLASSES%" (
    mkdir "%CLASSES%"
)

if errorlevel 1 (
    echo ERROR: Could not create build directory.
    echo.
    pause
    exit /b 1
)

REM --------------------------------------------------
REM Find every Java source under src
REM --------------------------------------------------

echo [2/3] Compiling all Java sources...
echo.

set "SOURCE_LIST=%BUILD%\sources.txt"

if exist "%SOURCE_LIST%" (
    del /f /q "%SOURCE_LIST%"
)

dir /s /b "%SRC%\*.java" > "%SOURCE_LIST%" 2>nul

if errorlevel 1 (
    echo ERROR: Unable to enumerate Java source files or none found.
    echo.
    pause
    exit /b 1
)

if not exist "%SOURCE_LIST%" (
    echo ERROR: No Java source files found.
    echo.
    pause
    exit /b 1
)

for %%F in ("%SOURCE_LIST%") do (
    if %%~zF EQU 0 (
        echo ERROR: No Java source files found.
        echo.
        pause
        exit /b 1
    )
)

echo Source files:
echo.

type "%SOURCE_LIST%"

echo.
echo --------------------------------------------------
echo.

"%JAVAC%" -encoding UTF-8 -d "%CLASSES%" @"%SOURCE_LIST%"

if errorlevel 1 (
    echo.
    echo ==================================================
    echo BUILD FAILED
    echo ==================================================
    echo.
    pause
    exit /b 1
)

echo.
echo Compilation successful.
echo.

REM --------------------------------------------------
REM Create JAR
REM --------------------------------------------------

echo Creating FSScanner.jar...
echo.

"%JAR%" --create --file "%JAR_FILE%" --main-class FSScanner -C "%CLASSES%" .

if errorlevel 1 (
    echo.
    echo ==================================================
    echo JAR CREATION FAILED
    echo ==================================================
    echo.
    pause
    exit /b 1
)

echo.
echo JAR created successfully:
echo %JAR_FILE%
echo.

REM --------------------------------------------------
REM Run
REM --------------------------------------------------

echo [3/3] Starting FS Scanner 365 Premiere...
echo.
echo --------------------------------------------------
echo.

"%JAVA%" -Xms64m -Xmx512m -jar "%JAR_FILE%"

set "EXIT_CODE=%ERRORLEVEL%"

echo.
echo --------------------------------------------------
echo.

if not "%EXIT_CODE%"=="0" (
    echo FS Scanner exited with code %EXIT_CODE%.
    echo.
    pause
    exit /b %EXIT_CODE%
)

echo FS Scanner closed normally.
echo.

exit /b 0