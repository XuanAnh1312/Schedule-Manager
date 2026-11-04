@echo off
setlocal
cd /d "%~dp0.."
where node >nul 2>nul
if errorlevel 1 (
  echo [LOI] Can Node.js 20 tro len trong PATH.
  exit /b 1
)
if not defined FRONTEND_PORT set "FRONTEND_PORT=8080"
if not defined BACKEND_URL set "BACKEND_URL=http://127.0.0.1:8081"
echo Frontend: http://localhost:%FRONTEND_PORT%
echo Mo dia chi tren bang trinh duyet. Nhan Ctrl+C de dung.
node "%~dp0frontend-server.mjs"
exit /b %errorlevel%
