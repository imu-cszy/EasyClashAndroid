@echo off
rem EasyClash bundle share - one click launcher.
rem
rem   double click            -> pick an .ecb file, then serve it
rem   drag an .ecb onto this  -> serve it directly
rem
rem The real work is done by easyclash-share.py next to this file; this
rem wrapper only resolves python and keeps the window open afterwards.

setlocal
rem Keep console and Python on the same code page so the Chinese banner in
rem easyclash-share.py is not mojibake (this .bat itself is ASCII only).
chcp 65001 >nul
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"

cd /d "%~dp0.."

set "BUNDLE=%~1"
if defined BUNDLE if not exist "%BUNDLE%" set "BUNDLE="

if not defined BUNDLE call :pick
if not defined BUNDLE (
    echo.
    echo No bundle selected - nothing to do.
    echo.
    pause
    exit /b 1
)

where python >nul 2>nul
if errorlevel 1 (
    echo.
    echo Python 3 was not found in PATH.
    echo Install it once with:  winget install Python.Python.3.12
    echo.
    pause
    exit /b 1
)

echo.
echo Serving: %BUNDLE%
echo.
python "%~dp0easyclash-share.py" "%BUNDLE%" %2 %3

echo.
echo Server stopped.
pause
exit /b 0

rem ---------------------------------------------------------------- helpers

:pick
set "PICKER=%TEMP%\easyclash-pick.ps1"
> "%PICKER%" echo Add-Type -AssemblyName System.Windows.Forms
>>"%PICKER%" echo $d = New-Object System.Windows.Forms.OpenFileDialog
>>"%PICKER%" echo $d.Filter = 'EasyClash bundle (*.ecb)^|*.ecb^|All files (*.*)^|*.*'
>>"%PICKER%" echo $d.Title = 'Select the .ecb bundle to share'
>>"%PICKER%" echo if ($d.ShowDialog() -eq 'OK') { Write-Output $d.FileName }

for /f "usebackq delims=" %%F in (`powershell -NoProfile -STA -ExecutionPolicy Bypass -File "%PICKER%"`) do set "BUNDLE=%%F"

del "%PICKER%" >nul 2>nul
exit /b 0
