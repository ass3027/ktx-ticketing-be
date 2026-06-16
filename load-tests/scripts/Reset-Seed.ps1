#requires -Version 7
<#
.SYNOPSIS
    부하 테스트 전 DB/Redis 초기화 + 앱 재기동 + health UP 대기 (Windows/PowerShell).
.DESCRIPTION
    bash reset.sh / make reset-seed 의 PowerShell 등가물. mysql/redis-cli 는 호스트가 아니라
    docker compose 서비스 컨테이너 안의 것을 호출하므로 호스트 설치 의존이 없다.

    실행 후 DataInitializer 가 'train COUNT == 0' 을 보고 50,000 좌석/10,000 user 를 재시드한다.
.PARAMETER HealthWaitSeconds
    앱 재기동 후 health UP 까지 대기할 최대 초. 기본 180 — JDK 25 + Spring Boot 4.0
    cold start + DataInitializer 50,000 좌석 시드를 합쳐 여유 있게.
.PARAMETER SkipAppRestart
    앱 재기동을 건너뛴다 (DB/Redis 만 비우고 끝). 디버깅용.
.EXAMPLE
    ./load-tests/scripts/Reset-Seed.ps1
#>
[CmdletBinding()]
param(
    [int]$HealthWaitSeconds = 180,
    [switch]$SkipAppRestart
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot
try {
    Write-Host '=== DB TRUNCATE ===' -ForegroundColor Cyan
    Get-Content load-tests/seed/reset.sql -Raw |
        docker compose exec -T mysql mysql -uktx -pktx1234 ktx_ticketing
    if ($LASTEXITCODE -ne 0) { throw "mysql reset 실패 (exit $LASTEXITCODE)" }

    Write-Host '=== Redis FLUSHDB ===' -ForegroundColor Cyan
    docker compose exec -T redis redis-cli FLUSHDB
    if ($LASTEXITCODE -ne 0) { throw "redis FLUSHDB 실패 (exit $LASTEXITCODE)" }

    if ($SkipAppRestart) {
        Write-Host '앱 재기동 건너뜀 (-SkipAppRestart).' -ForegroundColor Yellow
        return
    }

    Write-Host '=== 앱 재기동 ===' -ForegroundColor Cyan
    docker compose restart app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "app restart 실패 (exit $LASTEXITCODE)" }

    Write-Host "=== 앱 health UP 대기 (최대 ${HealthWaitSeconds}s) ===" -ForegroundColor Cyan
    $start = Get-Date
    $deadline = $start.AddSeconds($HealthWaitSeconds)
    $lastErr = '(아직 시도 없음)'
    $lastStatus = $null
    $nextProgress = 15
    while ((Get-Date) -lt $deadline) {
        try {
            # Invoke-RestMethod 사용 — Invoke-WebRequest 는 actuator 의 Content-Type
            # (application/vnd.spring-boot.actuator.v3+json) 을 못 알아봐 Content 를
            # byte[] 로 반환, ConvertFrom-Json 이 깨진다.
            $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 2 -ErrorAction Stop
            $lastStatus = $health.status
            if ($lastStatus -eq 'UP') {
                $elapsed = [int]((Get-Date) - $start).TotalSeconds
                Write-Host "health=UP — 시드 재적재 완료 (${elapsed}s)." -ForegroundColor Green
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
