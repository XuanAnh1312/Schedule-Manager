@echo off
setlocal
cd /d "%~dp0.."
if not exist pom.xml (
  echo [LOI] Khong tim thay pom.xml. Giu HUONG_DAN ben trong thu muc du an.
  exit /b 1
)
if not exist .env (
  echo [LOI] Chua co .env. Sao chep .env.example thanh .env va dien cau hinh SQL Server, JWT_SECRET.
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  echo [LOI] Can JDK 21 tro len trong PATH.
  exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
  echo [LOI] Can Maven trong PATH.
  exit /b 1
)
if not defined BACKEND_PORT set "BACKEND_PORT=8081"
set "MOC_MAVEN_OFFLINE="
if /i "%~1"=="--offline" set "MOC_MAVEN_OFFLINE=-o"
echo Dang dong goi backend. Lan dau can Internet de tai dependencies.
call mvn %MOC_MAVEN_OFFLINE% -B package -DskipTests
if errorlevel 1 (
  echo [LOI] Build that bai. Xem loi Maven phia tren.
  exit /b 1
)
echo Backend: http://localhost:%BACKEND_PORT%
echo Nhan Ctrl+C de dung. Flyway tu dong nang cap schema, khong xoa du lieu.
java -jar "target\ManageSchedule-1.0-SNAPSHOT.jar" --spring.profiles.active=sqlserver --server.port=%BACKEND_PORT%
exit /b %errorlevel%
