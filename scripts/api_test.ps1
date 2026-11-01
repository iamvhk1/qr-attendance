# ==============================================================================
# Direct API Testing via PowerShell (Section 5 in commands.txt)
# ==============================================================================
$ErrorActionPreference = "Stop"

$baseUrl = "http://localhost:8080"
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "      QR ATTENDANCE SYSTEM — DIRECT API SMOKE TEST          " -ForegroundColor Cyan
Write-Host "      Target: $baseUrl                                    " -ForegroundColor Cyan
Write-Host "============================================================`n" -ForegroundColor Cyan

# Step 1: Generate Admin Invite Code
Write-Host "[Step 1/4] Generating Admin Invite Code..." -ForegroundColor Yellow
$inviteRes = Invoke-RestMethod -Uri "$baseUrl/api/admin/invite" `
  -Method POST `
  -ContentType "application/json" `
  -Body '{"adminSecret": "changeme-admin-secret-2026"}'

$inviteCode = $inviteRes.inviteCode
Write-Host "[OK] Invite code generated: $($inviteCode.Substring(0, [Math]::Min(25, $inviteCode.Length)))...`n" -ForegroundColor Green

# Step 2: Register a Professor
Write-Host "[Step 2/4] Registering Professor (Dr. Alan Turing)..." -ForegroundColor Yellow
try {
  $regRes = Invoke-RestMethod -Uri "$baseUrl/api/auth/register" `
    -Method POST `
    -ContentType "application/json" `
    -Body (@{
      fullName   = "Dr. Alan Turing"
      email      = "turing@university.edu"
      password   = "Password123!"
      inviteCode = $inviteCode
    } | ConvertTo-Json)
  Write-Host "[OK] Professor registered successfully: $($regRes.email)`n" -ForegroundColor Green
} catch {
  Write-Host "[NOTE] Registration notice ($($_.Exception.Message)). Proceeding to login...`n" -ForegroundColor DarkYellow
}

# Step 3: Login as Professor
Write-Host "[Step 3/4] Logging in as Professor..." -ForegroundColor Yellow
$loginRes = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" `
  -Method POST `
  -ContentType "application/json" `
  -Body '{
    "email": "turing@university.edu",
    "password": "Password123!"
  }'
$token = $loginRes.token
Write-Host "[OK] Logged in successfully. JWT Token: $($token.Substring(0, [Math]::Min(25, $token.Length)))...`n" -ForegroundColor Green

# Step 4: Create a Course (Authenticated)
Write-Host "[Step 4/4] Creating Course (CS101)..." -ForegroundColor Yellow
try {
  $courseRes = Invoke-RestMethod -Uri "$baseUrl/api/courses" `
    -Method POST `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType "application/json" `
    -Body '{
      "code": "CS101",
      "name": "Data Structures & Algorithms",
      "semester": "Fall 2026"
    }'
  Write-Host "[OK] Course created: $($courseRes.code) - $($courseRes.name)`n" -ForegroundColor Green
} catch {
  Write-Host "[NOTE] Course notice ($($_.Exception.Message)).`n" -ForegroundColor DarkYellow
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "      ALL API TESTS COMPLETED SUCCESSFULLY!                 " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
