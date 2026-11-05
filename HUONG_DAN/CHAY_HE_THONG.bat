@echo off
setlocal
set "HRM_LAUNCH_SCRIPT=%~dp0CHAY_HE_THONG.ps1"
cd /d "%~dp0.."
set "HRM_LAUNCH_ARGS="
:arguments
if "%~1"=="" goto launch
if /i "%~1"=="--offline" (
  set "HRM_LAUNCH_ARGS=%HRM_LAUNCH_ARGS% -Offline"
  shift
  goto arguments
)
if /i "%~1"=="--no-browser" (
  set "HRM_LAUNCH_ARGS=%HRM_LAUNCH_ARGS% -NoBrowser"
  shift
  goto arguments
)
if /i "%~1"=="--rebuild" (
  set "HRM_LAUNCH_ARGS=%HRM_LAUNCH_ARGS% -Rebuild"
  shift
  goto arguments
)
if /i "%~1"=="--stop" (
  set "HRM_LAUNCH_ARGS=%HRM_LAUNCH_ARGS% -Stop"
  shift
  goto arguments
)
echo [LOI] Tham so khong hop le: %~1
exit /b 1
:launch
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%HRM_LAUNCH_SCRIPT%" %HRM_LAUNCH_ARGS%
set "HRM_EXIT=%errorlevel%"
if not "%HRM_EXIT%"=="0" (
  echo.
  echo [LOI] He thong chua khoi dong thanh cong. Xem thong bao va log phia tren.
  if not defined HRM_NO_PAUSE pause
)
exit /b %HRM_EXIT%
