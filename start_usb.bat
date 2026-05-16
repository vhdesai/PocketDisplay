@echo off
setlocal EnableExtensions

title PocketDisplay USB Launcher
set "ROOT=%~dp0"
set "SERVER_DIR=%ROOT%server"
set "CONFIG_FILE=%ROOT%configuration.yaml"
set "ADB="
set "PYTHON_CMD="

echo ============================================================
echo   PocketDisplay - USB Launcher
echo ============================================================
echo.

where adb >nul 2>&1
if not errorlevel 1 (
    for /f "delims=" %%I in ('where adb') do (
        set "ADB=%%I"
        goto :adb_found
    )
)
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

:adb_found
if not defined ADB (
    echo ERROR: adb.exe was not found.
    echo Install Android SDK Platform-Tools and add adb to PATH.
    echo Download: https://developer.android.com/tools/releases/platform-tools
    exit /b 1
)

echo [1/5] Using ADB: %ADB%
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo ERROR: No authorized USB Android device detected.
    echo.
    echo Make sure:
    echo   1. The phone is connected by USB
    echo   2. USB debugging is enabled
    echo   3. You accepted the RSA authorization prompt
    exit /b 1
)

where py >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_CMD=py -3"
    goto :python_found
)
where python >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_CMD=python"
    goto :python_found
)

echo ERROR: Python 3.10+ was not found.
exit /b 1

:python_found
call %PYTHON_CMD% -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"
if errorlevel 1 (
    echo ERROR: Python 3.10+ is required.
    exit /b 1
)

echo [2/5] Python command: %PYTHON_CMD%
if not exist "%CONFIG_FILE%" (
    echo [3/5] WARNING: configuration.yaml not found. Built-in defaults will be used.
) else (
    echo [3/5] Configuration file: %CONFIG_FILE%
)

if not exist "%SERVER_DIR%\server.py" (
    echo ERROR: Server entry point not found at %SERVER_DIR%\server.py
    exit /b 1
)

echo [4/5] Server directory: %SERVER_DIR%
echo [5/5] Starting server...
echo.
pushd "%SERVER_DIR%"
call %PYTHON_CMD% server.py
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo.
    echo Server exited with code %EXIT_CODE%.
    exit /b %EXIT_CODE%
)

echo.
echo PocketDisplay server stopped normally.
exit /b 0
