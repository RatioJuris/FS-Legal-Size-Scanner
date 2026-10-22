@echo off
setlocal EnableExtensions
title FS Legal Size Scanner - Release Builder

rem Build a repeatable release ZIP without modifying the application build output.
for %%I in ("%~dp0.") do set "APP_HOME=%%~fI"

set "VERSION=1.0.1"
set "MSI_SOURCE=%APP_HOME%\dist\msi\FSScanner-%VERSION%.msi"
set "RELEASE_ROOT=%APP_HOME%\dist\releases"
set "STAGE=%RELEASE_ROOT%\FSScanner-%VERSION%-Release"
set "ZIP=%RELEASE_ROOT%\FSScanner-%VERSION%-Release.zip"
set "ZIP_TEMP=%RELEASE_ROOT%\FSScanner-%VERSION%-Release.tmp.zip"
set "MSI_COPY=%STAGE%\FSScanner-%VERSION%.msi"
set "CHECKSUM_FILE=%STAGE%\SHA256SUMS.txt"

echo.
echo ================================================
echo   FS Legal Size Scanner %VERSION% Release Builder
echo ================================================
echo.

if not exist "%MSI_SOURCE%" (
    echo ERROR: MSI not found:
    echo   "%MSI_SOURCE%"
    echo Build the MSI first, then run Release.bat again.
    exit /b 1
)

for %%F in (README.txt EULA.txt LICENSE.txt CREDITS.txt) do (
    if not exist "%APP_HOME%\%%F" (
        echo ERROR: Required release file is missing: %%F
        exit /b 1
    )
)

if not exist "%RELEASE_ROOT%" mkdir "%RELEASE_ROOT%"
if errorlevel 1 (
    echo ERROR: Could not create release directory.
    exit /b 1
)

rem Rebuild only this dedicated staging directory.
if exist "%STAGE%" rmdir /s /q "%STAGE%"
if errorlevel 1 (
    echo ERROR: Could not clean release staging directory.
    exit /b 1
)

mkdir "%STAGE%"
if errorlevel 1 (
    echo ERROR: Could not create release staging directory.
    exit /b 1
)

echo [1/5] Copying MSI and documentation...
copy /y "%MSI_SOURCE%" "%MSI_COPY%" >nul
if errorlevel 1 goto :copy_error

for %%F in (README.txt EULA.txt LICENSE.txt CREDITS.txt) do (
    copy /y "%APP_HOME%\%%F" "%STAGE%\%%F" >nul
    if errorlevel 1 goto :copy_error
)

echo [2/5] Generating MSI SHA-256 checksum...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$h = (Get-FileHash -LiteralPath $env:MSI_COPY -Algorithm SHA256).Hash; [IO.File]::WriteAllText($env:CHECKSUM_FILE, ($h + '  FSScanner-%VERSION%.msi' + [Environment]::NewLine), [Text.Encoding]::ASCII)"
if errorlevel 1 (
    echo ERROR: Failed to generate SHA256SUMS.txt.
    exit /b 1
)
if not exist "%CHECKSUM_FILE%" (
    echo ERROR: SHA256SUMS.txt was not created.
    exit /b 1
)

echo [3/5] Creating release ZIP...
if exist "%ZIP_TEMP%" del /q "%ZIP_TEMP%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "Compress-Archive -Path (Join-Path $env:STAGE '*') -DestinationPath $env:ZIP_TEMP -CompressionLevel Optimal"
if errorlevel 1 (
    echo ERROR: Failed to create release ZIP.
    if exist "%ZIP_TEMP%" del /q "%ZIP_TEMP%"
    exit /b 1
)
if not exist "%ZIP_TEMP%" (
    echo ERROR: Temporary release ZIP was not created.
    exit /b 1
)

echo [4/5] Verifying ZIP contents and embedded checksum...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.IO.Compression.FileSystem; $z=[IO.Compression.ZipFile]::OpenRead($env:ZIP_TEMP); try { $names=@($z.Entries | ForEach-Object { $_.FullName }); $need=@('FSScanner-%VERSION%.msi','README.txt','EULA.txt','LICENSE.txt','CREDITS.txt','SHA256SUMS.txt'); $missing=@($need | Where-Object { $_ -notin $names }); if ($missing.Count) { throw ('ZIP is missing: ' + ($missing -join ', ')) }; $entry=$z.GetEntry('SHA256SUMS.txt'); $reader=[IO.StreamReader]::new($entry.Open()); try { $line=$reader.ReadToEnd().Trim() } finally { $reader.Dispose() }; $expected=($line -split '\s+')[0]; $msiEntry=$z.GetEntry('FSScanner-%VERSION%.msi'); $stream=$msiEntry.Open(); $sha=[Security.Cryptography.SHA256]::Create(); try { $actual=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','') } finally { $stream.Dispose(); $sha.Dispose() }; if ($actual -ne $expected) { throw 'Checksum inside ZIP does not match the MSI in ZIP.' }; 'Verified files:'; $names; 'Embedded MSI SHA-256: ' + $actual } finally { $z.Dispose() }"
if errorlevel 1 (
    echo ERROR: Release ZIP verification failed.
    if exist "%ZIP_TEMP%" del /q "%ZIP_TEMP%"
    exit /b 1
)

echo [5/5] Publishing ZIP to the local release folder...
move /y "%ZIP_TEMP%" "%ZIP%" >nul
if errorlevel 1 (
    echo ERROR: Could not move verified ZIP into place.
    exit /b 1
)

echo.
echo SUCCESS: Release package created and verified.
echo.
echo ZIP:
echo   "%ZIP%"
echo.
echo SHA-256:
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "(Get-FileHash -LiteralPath $env:ZIP -Algorithm SHA256) | Format-List Algorithm,Hash,Path"
echo.
echo Contents:
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::OpenRead($env:ZIP).Entries | Select-Object FullName,Length | Format-Table -AutoSize"
echo.
exit /b 0

:copy_error
echo ERROR: A release file could not be copied.
exit /b 1
