#requires -Version 7
<#
.SYNOPSIS
    T4-3 — L1 단일 좌석 동시 경쟁(정합성) 측정 N회 반복 실행기.
.DESCRIPTION
    매 회: Reset-Seed.ps1 (DB/Redis 초기화 + 앱 재기동 + health 대기)
          → k6 run L1_single_seat_race.js
          → Invoke-PostRunCheck.ps1 (DB/Redis 정합성)
    raw 출력은 load-tests/results/L1_run_$i.{txt,check.txt} 로 저장 (gitignore).

    호스트에 k6 가 설치돼 있어야 한다. mysql/redis-cli 는 컨테이너 경유라 호스트 의존 없음.

    K(입장 제어 상한) 우회는 docker-compose.override.yml 의 BOOKING_ADMISSION_MAX_ACTIVE
    환경변수로 처리. 이 스크립트는 앱 컨테이너가 이미 그 값을 받은 상태로 떠 있다고 가정한다
    (`docker compose up -d --force-recreate app` 을 먼저 한 번 실행).
.PARAMETER Iterations
    반복 회수. 기본 3 (KTX_Ticketing_Performance_Test.md §0.2 "반복" 원칙).
.PARAMETER ScheduleId
    L1 대상 스케줄 ID (k6 env, 정합성 검증). 기본 1.
.PARAMETER SeatInventoryId
    L1 인기 좌석 재고 ID (k6 env, 정합성 검증). 기본 1.
.EXAMPLE
    pwsh load-tests/scripts/Run-L1.ps1
.EXAMPLE
    pwsh load-tests/scripts/Run-L1.ps1 -Iterations 5
#>
[CmdletBinding()]
param(
    [int]$Iterations = 3,
    [int]$ScheduleId = 1,
    [int]$SeatInventoryId = 1
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot
try {
    if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
        throw 'k6 명령을 찾을 수 없다. https://grafana.com/docs/k6/latest/set-up/install-k6/ 참고.'
    }

    $resultsDir = 'load-tests/results'
    if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Force $resultsDir | Out-Null }

    $env:K6_WEB_DASHBOARD = 'true'
    $env:SCHEDULE_ID = "$ScheduleId"
    $env:SEAT_INVENTORY_ID = "$SeatInventoryId"

    for ($i = 1; $i -le $Iterations; $i++) {
        Write-Host "`n############### L1 회차 $i / $Iterations ###############" -ForegroundColor Cyan

        & "$PSScriptRoot/Reset-Seed.ps1"

        $runLog = Join-Path $resultsDir "L1_run_$i.txt"
        Write-Host "k6 실행 → $runLog" -ForegroundColor Cyan
        k6 run load-tests/scenarios/L1_single_seat_race.js 2>&1 | Tee-Object -FilePath $runLog
        $k6Exit = $LASTEXITCODE

        $checkLog = Join-Path $resultsDir "L1_run_$i.check.txt"
        & "$PSScriptRoot/Invoke-PostRunCheck.ps1" -ScheduleId $ScheduleId -SeatInventoryId $SeatInventoryId -OutputPath $checkLog

        if ($k6Exit -ne 0) {
            Write-Warning "회차 $i k6 종료 코드 $k6Exit (threshold 불합격 가능 — $runLog 확인)."
        } else {
            Write-Host "회차 $i 완료." -ForegroundColor Green
        }
    }

    Write-Host "`n전체 회차 종료. 결과는 $resultsDir 에 누적, 표는 docs/P4_Result.md T4-3 섹션에 기입." -ForegroundColor Green
}
finally {
    Pop-Location
}
