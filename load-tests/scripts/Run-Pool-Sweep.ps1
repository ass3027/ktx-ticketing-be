#requires -Version 7
<#
.SYNOPSIS
    T4-5 ① — HikariCP 풀 크기 독립 측정(A1). pool 값별로 L3(조회 폭주)를 반복 실행한다.
.DESCRIPTION
    baseline(SCARD 직렬·캐시 off)에서 DB_POOL_SIZE 만 바꿔가며 L3 를 돌려 포화점·역효과를 본다.
    각 pool 값은 Run-Scenario-Container.ps1 -DbPoolSize <N> 으로 위임. 결과는 pool 별 prefix 로 분리.
    첫 pool 값의 첫 회차만 -Build(yml/코드 반영). 이후는 같은 이미지로 env 토글만.
.PARAMETER PoolSizes
    측정할 풀 크기 목록. 기본 10,20,30,50 (10=Before). 2코어 환경 이론값≈5.
.PARAMETER Iterations
    pool 값당 L3 회차 수. 기본 2(일관성 확인).
#>
[CmdletBinding()]
param(
    [int[]]$PoolSizes = @(10, 20, 30, 50),
    [int]$Iterations = 2
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot
try {
    $first = $true
    foreach ($pool in $PoolSizes) {
        Write-Host "`n=================== POOL=$pool ($Iterations 회) ===================" -ForegroundColor Magenta
        $args = @{
            Scenario       = 'load-tests/scenarios/L3_list_query.js'
            ResultPrefix   = "L3_pool$pool"
            Iterations     = $Iterations
            AdmissionMax   = 2000      # 조회 부하 — 입장 제어 우회
            DbPoolSize     = $pool
        }
        # 첫 pool 의 첫 실행만 재빌드(yml 의 maximum-pool-size 바인딩을 이미지에 반영).
        if ($first) { $args['Build'] = $true; $first = $false }
        & "$PSScriptRoot/Run-Scenario-Container.ps1" @args
    }
    Write-Host "`n전체 pool sweep 종료. 결과: load-tests/results/L3_pool*_container_run_*.txt" -ForegroundColor Green
}
finally {
    Pop-Location
}
