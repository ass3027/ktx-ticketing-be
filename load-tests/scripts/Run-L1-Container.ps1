#requires -Version 7
<#
.SYNOPSIS
    T4-3 — L1 단일 좌석 동시 경쟁(정합성) 측정 — k6 컨테이너 실행판 (thin wrapper).
.DESCRIPTION
    실행 로직은 Run-Scenario-Container.ps1 로 일반화됐다. 이 래퍼는 L1 시나리오 경로와
    정합성 검증(-PostRunCheck)을 고정해 호출 편의를 유지한다.
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

& "$PSScriptRoot/Run-Scenario-Container.ps1" `
    -Scenario 'load-tests/scenarios/L1_single_seat_race.js' `
    -ResultPrefix 'L1' `
    -Iterations $Iterations -ScheduleId $ScheduleId -SeatInventoryId $SeatInventoryId `
    -HealthWaitSeconds $HealthWaitSeconds -PostRunCheck
