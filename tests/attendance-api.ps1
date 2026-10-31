# Issue #8 API smoke. SAFE by default: read-only, does not check in/out or approve.
param(
  [string]$Base = 'http://localhost:8081',
  [string]$PeriodId = 'qa_attendance_period',
  [string]$Username = 'admin',
  [string]$Password = 'Admin@123'
)
$ErrorActionPreference='Stop'
$baseHeaders=@{'Accept'='application/json'}
function Http($method,$path,$headers,$body=$null) {
  $args=@{ Uri="$Base$path";Method=$method;Headers=$headers;ErrorAction='Stop' }
  if($null -ne $body) {$args.ContentType='application/json';$args.Body=($body|ConvertTo-Json -Depth 10)}
  return Invoke-RestMethod @args
}
Write-Host '--- 1. Health API ---'
Http 'GET' '/api/health/database' $baseHeaders | ConvertTo-Json
Write-Host '--- 2. Login JWT ---'
$login=Http 'POST' '/api/auth/login' $baseHeaders @{username=$Username;password=$Password}
if(-not $login.token){throw 'No token returned'}
$auth=@{'Authorization'="Bearer $($login.token)"}
Write-Host '--- 3. Current identity ---'
$me=Http 'GET' '/api/attendance/me' $auth
$me|ConvertTo-Json
Write-Host '--- 4. Periods ---'
Http 'GET' '/api/reports/periods' $auth|ConvertTo-Json
Write-Host '--- 5. Dashboard, period may require fixture ---'
$dashboard=Http 'GET' "/api/reports/dashboard?periodId=$([uri]::EscapeDataString($PeriodId))" $auth
$dashboard|ConvertTo-Json
Write-Host '--- 6. Formula 808/740 = 109% (separate pure JUnit test) ---'
Write-Host '--- 7. Protected route unauthorized must be 401 ---'
try {
  Invoke-WebRequest -Uri "$Base/api/attendance/records" -Method GET -ErrorAction Stop|Out-Null
  throw 'SECURITY FAILURE: anonymous request accepted'
} catch {
  $code=$_.Exception.Response.StatusCode.value__
  if($code -ne 401){throw "Expected 401; got $code"}
  Write-Host 'PASS: anonymous 401'
}
Write-Host 'PASS: non-destructive API smoke complete'
Write-Host 'For concurrent check-in/review and 403 scope cases, follow Docs/issue8-attendance.md (manual controlled tests).'
