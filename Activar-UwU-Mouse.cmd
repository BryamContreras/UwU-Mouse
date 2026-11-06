@echo off
setlocal
title Activar UwU Mouse
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-UwUMouse.ps1" %*
set "uwuResult=%errorlevel%"
echo.
if not "%uwuResult%"=="0" echo La preparacion no termino. Revisa el mensaje anterior.
pause
exit /b %uwuResult%
