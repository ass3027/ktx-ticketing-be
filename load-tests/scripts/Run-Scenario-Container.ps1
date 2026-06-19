#requires -Version 7
<#
.SYNOPSIS
    L1~L6 부하 시나리오 — k6 컨테이너 실행판 (시나리오 무관 일반화).
.DESCRIPTION
    k6 를 app 과 같은 docker 네트워크에서 실행한다(`docker-compose.k6.yml`, BASE_URL=http://app:8080).
    컨테이너↔컨테이너 통신이라 Windows Docker Desktop 의 포트 프록시(NAT) 와 호스트 ephemeral
    포트/TIME_WAIT 압박을 모두 우회 → refused=0 으로 진짜 N 동시 부하를 측정한다.
    (Run-L1-Container.ps1 을 시나리오 경로/결과 prefix 파라미터로 일반화한 것. L2~L6 공용.)

    매 회: TRUNCATE+FLUSHDB → app 재기동(admission=2000 env) → health 대기
          → 컨테이너 k6 run → (옵션) Invoke-PostRunCheck.

    app 재기동과 k6 실행은 *동일한* compose 조합(-f base -f k6)을 쓴다. 조합이 일치해야
    `docker compose run k6` 가 app 을 재생성하지 않는다. admission(K) 우회는 환경변수로
    주입한다(base 가 ${BOOKING_ADMISSION_MAX_ACTIVE:-100} 로 받음).
.PARAMETER Scenario
    실행할 시나리오 파일 경로(리포 루트 기준). 예: load-tests/scenarios/L2_normal_flow.js
.PARAMETER ResultPrefix
    결과 파일 prefix. 예: L2 → results/L2_container_run_$i.txt. 미지정 시 시나리오 파일명에서 유추.
.PARAMETER Iterations
    반복 회수. 기본 3.
.PARAMETER PostRunCheck
    매 회차 후 Invoke-PostRunCheck(DB/Redis 정합성) 실행 여부. L1/L2b 처럼 정합성을 단언하는
    시나리오에서만 켠다. L2(혼합 부하)는 기본 끔.
.EXAMPLE
    pwsh load-tests/scripts/Run-Scenario-Container.ps1 -Scenario load-tests/scenarios/L2_normal_flow.js -Iterations 3
.EXAMPLE
    pwsh load-tests/scripts/Run-Scenario-Container.ps1 -Scenario load-tests/scenarios/L2b_auto_assign.js -PostRunCheck
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Scenario,
    [string]$ResultPrefix,
    [int]$Iterations = 3,
    [int]$ScheduleId = 1,
    [int]$SeatInventoryId = 1,
    [int]$HealthWaitSeconds = 120,
    [switch]$PostRunCheck
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot

if (-not $ResultPrefix) {
    # 시나리오 파일명 앞 토큰(L2_normal_flow → L2) 을 prefix 로.
    $ResultPrefix = (Split-Path -Leaf $Scenario) -replace '_.*$','' -replace '\.js$',''
}

# 단일 compose 조합 — app 재기동도 k6 실행도 동일 -f 세트. admission 우회는 env.
$compose = @('-f', 'docker-compose.yml', '-f', 'docker-compose.k6.yml')
$env:BOOKING_ADMISSION_MAX_ACTIVE = '2000'

# k6 컨테이너 안의 시나리오 경로 (load-tests 가 /work/load-tests 로 마운트됨).
$containerScenario = "/work/" + ($Scenario -replace '\\','/')

try {
    if (-not (Test-Path $Scenario)) { throw "시나리오 없음: $Scenario" }
    $resultsDir = 'load-tests/results'
    if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Force $resultsDir | Out-Null }

    for ($i = 1; $i -le $Iterations; $i++) {
        Write-Host "`n############### $ResultPrefix(Container) 회차 $i / $Iterations ###############" -ForegroundColor Cyan

        Write-Host '=== DB TRUNCATE ===' -ForegroundColor Cyan
        Get-Content load-tests/seed/reset.sql -Raw | docker compose exec -T mysql mysql -uktx -pktx1234 ktx_ticketing
        if ($LASTEXITCODE -ne 0) { throw "mysql reset 실패 (exit $LASTEXITCODE)" }
        Write-Host '=== Redis FLUSHDB ===' -ForegroundColor Cyan
        docker compose exec -T redis redis-cli FLUSHDB | Out-Null

        # 매 회차 app 재생성 — reset 후 seed/워밍업(DataInitializer/AvailPoolWarmup, 부팅 1회)을
        # 다시 태운다. AOT 캐시는 named volume 에 보존돼 재 dump 없이 빠르게 뜬다.
        Write-Host '=== app 재생성 (단일 조합, admission=2000 env) ===' -ForegroundColor Cyan
        docker compose @compose up -d --force-recreate app 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "app 재기동 실패 (exit $LASTEXITCODE)" }

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

        $runLog = Join-Path $resultsDir "${ResultPrefix}_container_run_$i.txt"
        Write-Host "k6(컨테이너) 실행 → $runLog" -ForegroundColor Cyan
        docker compose @compose run --rm `
            -e SCHEDULE_ID=$ScheduleId -e SEAT_INVENTORY_ID=$SeatInventoryId `
            k6 run $containerScenario 2>&1 |
            Tee-Object -FilePath $runLog | Out-Null
        $k6Exit = $LASTEXITCODE

        if ($PostRunCheck) {
            $checkLog = Join-Path $resultsDir "${ResultPrefix}_container_run_$i.check.txt"
            & "$PSScriptRoot/Invoke-PostRunCheck.ps1" -ScheduleId $ScheduleId -SeatInventoryId $SeatInventoryId -OutputPath $checkLog
        }

        $clean = (Get-Content $runLog -Raw) -replace '\x1b\[[0-9;]*m',''
        $refused = ([regex]::Matches($clean, 'actively refused|connection refused|dial tcp')).Count
        $reqs = if ($clean -match 'http_reqs[\.\s]+:\s*(\d+)') { $matches[1] } else { '?' }
        Write-Host "회차 $i : refused=$refused, http_reqs=$reqs, k6Exit=$k6Exit" -ForegroundColor $(if ($k6Exit -eq 0) { 'Green' } else { 'Yellow' })
    }

    Write-Host "`n전체 회차 종료. 결과: $resultsDir/${ResultPrefix}_container_run_*.txt" -ForegroundColor Green
}
finally {
    Pop-Location
}
