#requires -Version 7
<#
.SYNOPSIS
    T4-3 — L1 단일 좌석 동시 경쟁(정합성) 측정 — k6 컨테이너 실행판.
.DESCRIPTION
    k6 를 app 과 같은 docker 네트워크에서 실행한다(`docker-compose.k6.yml`, BASE_URL=http://app:8080).
    컨테이너↔컨테이너 통신이라 Windows Docker Desktop 의 포트 프록시(NAT) 와 호스트 ephemeral
    포트/TIME_WAIT 압박을 모두 우회 → refused=0 으로 진짜 1,000 동시 경쟁을 측정한다.
    (호스트 JVM 우회판 Run-L1-Host.ps1 의 후속 — Docker 만으로 실서버에 근접한 측정.)

    매 회: TRUNCATE+FLUSHDB → app 재기동(override 포함 = admission 2000 보장) → health 대기
          → 컨테이너 k6 run → Invoke-PostRunCheck.

    중요: app 재기동은 `-f docker-compose.yml -f docker-compose.override.yml` 로 한다.
    override(BOOKING_ADMISSION_MAX_ACTIVE=2000)를 빠뜨리면 입장 제어(K=100)에 막혀
    100 VU 만 경쟁에 진입한다. k6 실행만 `-f ... -f docker-compose.k6.yml` 로 분리.
.PARAMETER Iterations
    반복 회수. 기본 3.
.EXAMPLE
    pwsh load-tests/scripts/Run-L1-Container.ps1 -Iterations 3
#>
[CmdletBinding()]
param(
    [int]$Iterations = 3,
    [int]$ScheduleId = 1,
    [int]$SeatInventoryId = 1,
    [int]$HealthWaitSeconds = 120
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot

# 단일 compose 조합 — app 재기동도 k6 실행도 *동일한* -f 세트를 쓴다. 조합이 일치해야
# `docker compose run k6` 가 app 을 "원하는 상태와 다름"으로 보고 재생성하는 일이 없다.
# admission(K) 우회는 별도 override 파일이 아니라 환경변수로 주입(base 가 ${...:-100} 로 받음).
$compose = @('-f', 'docker-compose.yml', '-f', 'docker-compose.k6.yml')
$env:BOOKING_ADMISSION_MAX_ACTIVE = '2000'

try {
    $resultsDir = 'load-tests/results'
    if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Force $resultsDir | Out-Null }

    for ($i = 1; $i -le $Iterations; $i++) {
        Write-Host "`n############### L1(Container) 회차 $i / $Iterations ###############" -ForegroundColor Cyan

        Write-Host '=== DB TRUNCATE ===' -ForegroundColor Cyan
        Get-Content load-tests/seed/reset.sql -Raw | docker compose exec -T mysql mysql -uktx -pktx1234 ktx_ticketing
        if ($LASTEXITCODE -ne 0) { throw "mysql reset 실패 (exit $LASTEXITCODE)" }
        Write-Host '=== Redis FLUSHDB ===' -ForegroundColor Cyan
        docker compose exec -T redis redis-cli FLUSHDB | Out-Null

        # 매 회차 app 재생성 — 단일 조합($compose)으로. reset(TRUNCATE+FLUSHDB) 후 seed/워밍업을
        # 다시 태우려면 재기동이 필요하다(DataInitializer/AvailPoolWarmup 은 부팅 1회만 실행).
        # --force-recreate 라도 AOT 캐시는 named volume 에 보존돼 재 dump 없이 빠르게 뜬다.
        Write-Host '=== app 재생성 (단일 조합, admission=2000 env) ===' -ForegroundColor Cyan
        docker compose @compose up -d --force-recreate app 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "app 재기동 실패 (exit $LASTEXITCODE)" }

        # healthcheck(service_healthy)가 준비를 보장하지만, 다음 reset/측정 로직이 호스트에서
        # 도므로 호스트 8080 health 도 한 번 확인한다(이중 안전). 컨테이너망 도달성은 k6 의
        # depends_on: service_healthy 가 자동 보장하므로 수동 nc 가드는 제거.
        Write-Host "=== app healthy 대기 (최대 ${HealthWaitSeconds}s) ===" -ForegroundColor Cyan
        $deadline = (Get-Date).AddSeconds($HealthWaitSeconds)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            if ((docker inspect ktx-ticketing-be-app-1 --format '{{.State.Health.Status}}' 2>$null) -eq 'healthy') {
                $ready = $true; break
            }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) { throw "app healthy 시간초과" }
        $adm = (docker compose exec -T app sh -c 'echo $BOOKING_ADMISSION_MAX_ACTIVE' 2>$null).Trim()
        if ($adm -ne '2000') { throw "admission 보장 실패: BOOKING_ADMISSION_MAX_ACTIVE=$adm (기대 2000)" }
        Write-Host "app healthy, admission=$adm" -ForegroundColor Green

        $runLog = Join-Path $resultsDir "L1_container_run_$i.txt"
        Write-Host "k6(컨테이너) 실행 → $runLog" -ForegroundColor Cyan
        # depends_on: service_healthy 가 app 준비를 보장하고, 단일 조합이라 app 재생성도 없다.
        # → --no-deps 불필요(의존관계를 구조적으로 유지).
        docker compose @compose run --rm `
            -e SCHEDULE_ID=$ScheduleId -e SEAT_INVENTORY_ID=$SeatInventoryId `
            k6 run /work/load-tests/scenarios/L1_single_seat_race.js 2>&1 |
            Tee-Object -FilePath $runLog | Out-Null
        $k6Exit = $LASTEXITCODE

        $checkLog = Join-Path $resultsDir "L1_container_run_$i.check.txt"
        & "$PSScriptRoot/Invoke-PostRunCheck.ps1" -ScheduleId $ScheduleId -SeatInventoryId $SeatInventoryId -OutputPath $checkLog

        $clean = (Get-Content $runLog -Raw) -replace '\x1b\[[0-9;]*m',''
        $refused = ([regex]::Matches($clean, 'actively refused|connection refused|dial tcp')).Count
        $reqs = if ($clean -match 'http_reqs[\.\s]+:\s*(\d+)') { $matches[1] } else { '?' }
        Write-Host "회차 $i : refused=$refused, http_reqs=$reqs, k6Exit=$k6Exit" -ForegroundColor $(if ($k6Exit -eq 0) { 'Green' } else { 'Yellow' })
    }

    Write-Host "`n전체 회차 종료. 결과: $resultsDir/L1_container_run_*.{txt,check.txt}" -ForegroundColor Green
}
finally {
    Pop-Location
}
