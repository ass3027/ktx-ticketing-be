#requires -Version 7
<#
.SYNOPSIS
    T4-5 ② — 조회 SCARD tx-밖 토글 측정. 같은 pool 에서 RedisOutsideTx off/on 으로 L3(조회 폭주)를 반복 실행한다.
.DESCRIPTION
    pool 을 고정하고 tx 토글만 바꿔 L3 를 돌려 usage(커넥션 점유시간) 하락 → 포화점 변화를 본다.
    각 토글은 Run-Scenario-Container.ps1 -RedisOutsideTx <off|on> 으로 위임. 결과는 토글별 prefix 로 분리.
    첫 실행만 -Build(코드/yml 반영). 이후는 같은 이미지로 env 토글만.

    측정 매핑(docs/plans/Query_Path_Optimization_Plan.md §4):
      A2(독립)  : -PoolSize 10  (off=baseline vs on=tx밖 단독 효과)
      C2(누적)  : -PoolSize <①확정값> (off=C1 재측정 vs on=pool+tx밖 누적)
.PARAMETER PoolSize
    측정 중 고정할 HikariCP 풀 크기. 기본 10(A2 baseline). C2 는 ① 확정 pool 값을 준다.
.PARAMETER RedisOutsideTx
    스윕할 토글 목록. 기본 false,true (Before → After). 순서대로 실행.
.PARAMETER Iterations
    토글당 L3 회차 수. 기본 3(일관성 확인).
#>
[CmdletBinding()]
param(
    [int]$PoolSize = 10,
    [ValidateSet('true','false')][string[]]$RedisOutsideTx = @('false', 'true'),
    [int]$Iterations = 3
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot
try {
    $first = $true
    foreach ($tx in $RedisOutsideTx) {
        $label = if ($tx -eq 'true') { 'on' } else { 'off' }
        Write-Host "`n=========== POOL=$PoolSize · redisOutsideTx=$tx ($Iterations 회) ===========" -ForegroundColor Magenta
        $args = @{
            Scenario       = 'load-tests/scenarios/L3_list_query.js'
            ResultPrefix   = "L3_pool${PoolSize}_tx${label}"
            Iterations     = $Iterations
            AdmissionMax   = 2000        # 조회 부하 — 입장 제어 우회
            DbPoolSize     = $PoolSize
            RedisOutsideTx = $tx
        }
        # 첫 토글의 첫 실행만 재빌드(코드/yml 을 이미지에 반영). 이후는 env 토글만.
        if ($first) { $args['Build'] = $true; $first = $false }
        & "$PSScriptRoot/Run-Scenario-Container.ps1" @args
    }
    Write-Host "`n전체 tx sweep 종료. 결과: load-tests/results/L3_pool${PoolSize}_tx*_container_run_*.txt" -ForegroundColor Green
}
finally {
    Pop-Location
}
