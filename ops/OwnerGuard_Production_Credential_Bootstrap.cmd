@echo off
setlocal
title OwnerGuard 1.0.44 Production Bootstrap
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0OwnerGuard_Production_Credential_Bootstrap.ps1"
set "ec=%ERRORLEVEL%"
echo.
if not "%ec%"=="0" (
  echo OwnerGuard bootstrap stopped with error code %ec%.
  echo Review the message above. No secret values should be shown.
) else (
  echo OwnerGuard bootstrap finished successfully.
)
pause
exit /b %ec%
