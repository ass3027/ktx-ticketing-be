#requires -Version 7
<#
.SYNOPSIS
    L1 측정용 입장 제어(K) 우회 토글 — docker-compose.override.yml 생성/삭제 + 앱 recreate.
.DESCRIPTION
    L1(단일 좌석 정합성)은 1,000 VU 가 좌석 경쟁까지 도달해야 의미가 있으므로 운영 잠정값
    (max-active=100)을 우회해야 한다. 이 스크립트는 그 우회 토글만 책임진다 — DB/Redis 시드는
    건드리지 않는다 (그쪽은 Reset-Seed.ps1).

    -On  : docker-compose.override.yml 에 BOOKING_ADMISSION_MAX_ACTIVE 주입 →
           docker compose up -d --force-recreate app → health UP 대기.
    -Off : override 파일 삭제 → 같은 방식으로 app recreate → 운영 K 값 복원.

    override 파일은 gitignore 대상이라 커밋되지 않는다. 측정 종료 후 -Off 로 반드시 복원할 것.
.PARAMETER On
    K 우회 활성화 (override 생성). 아무 스위치도 없으면 기본값.
.PARAMETER Off
    K 우회 해제 (override 삭제 후 운영값 복원). -On 과 상호배타.
.PARAMETER MaxActive
    -On 일 때 주입할 BOOKING_ADMISSION_MAX_ACTIVE 값. 기본 2000.
.PARAMETER HealthWaitSeconds
    app recreate 후 health UP 까지 대기할 최대 초. 기본 180 (Reset-Seed.ps1 과 동일).
.EXAMPLE
    ./load-tests/scripts/Set-AdmissionOverride.ps1 -On
.EXAMPLE
    ./load-tests/scripts/Set-AdmissionOverride.ps1 -On -MaxActive 5000
.EXAMPLE
    ./load-tests/scripts/Set-AdmissionOverride.ps1 -Off
#>
[CmdletBinding(DefaultParameterSetName = 'On')]
param(
    [Parameter(ParameterSetName = 'On')]
    [switch]$On,
    [Parameter(ParameterSetName = 'Off', Mandatory = $true)]
    [switch]$Off,
    [Parameter(ParameterSetName = 'On')]
    [int]$MaxActive = 2000,
    [int]$HealthWaitSeconds = 180
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot
try {
    $overridePath = Join-Path $repoRoot 'docker-compose.override.yml'

    if ($Off) {
        if (Test-Path $overridePath) {
            Remove-Item $overridePath -Force
            Write-Host "=== docker-compose.override.yml 삭제 (K 운영값 복원) ===" -ForegroundColor Cyan
        } else {
            Write-Host 'docker-compose.override.yml 이 이미 없음 — 삭제 건너뜀.' -ForegroundColor Yellow
        }
    } else {
        $yaml = @"
services:
  app:
    environment:
      BOOKING_ADMISSION_MAX_ACTIVE: $MaxActive
"@
        Set-Content -Path $overridePath -Value $yaml -Encoding utf8
        Write-Host "=== docker-compose.override.yml 생성 (BOOKING_ADMISSION_MAX_ACTIVE=$MaxActive) ===" -ForegroundColor Cyan
    }

    Write-Host '=== app force-recreate ===' -ForegroundColor Cyan
    docker compose up -d --force-recreate app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "app recreate 실패 (exit $LASTEXITCODE)" }

    Write-Host "=== 앱 health UP 대기 (최대 ${HealthWaitSeconds}s) ===" -ForegroundColor Cyan
    $start = Get-Date
    $deadline = $start.AddSeconds($HealthWaitSeconds)
    $lastErr = '(아직 시도 없음)'
    $lastStatus = $null
    $nextProgress = 15
    while ((Get-Date) -lt $deadline) {
        try {
            # Invoke-RestMethod 사용 — actuator Content-Type 문제는 Reset-Seed.ps1 주석 참조.
            $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 2 -ErrorAction Stop
            $lastStatus = $health.status
            if ($lastStatus -eq 'UP') {
                $elapsed = [int]((Get-Date) - $start).TotalSeconds
                $mode = if ($Off) { 'K 복원' } else { "K 우회(max-active=$MaxActive)" }
                Write-Host "health=UP — $mode 적용 완료 (${elapsed}s)." -ForegroundColor Green
                return
            }
        } catch {
            $lastErr = $_.Exception.Message
        }
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        if ($elapsed -ge $nextProgress) {
            $statusInfo = if ($lastStatus) { "lastStatus=$lastStatus" } else { "lastErr=$lastErr" }
            Write-Host "  ...${elapsed}s 경과 ($statusInfo)" -ForegroundColor DarkGray
            $nextProgress += 15
        }
        Start-Sleep -Seconds 3
    }
    $statusInfo = if ($lastStatus) { "마지막 status=$lastStatus" } else { "마지막 오류=$lastErr" }
    throw "health UP 대기 시간 초과 (${HealthWaitSeconds}s). $statusInfo. `docker compose logs app` 확인 권장."
}
finally {
    Pop-Location
}
