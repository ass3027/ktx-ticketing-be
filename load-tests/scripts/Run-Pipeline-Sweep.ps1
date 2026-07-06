#requires -Version 7
<#
.SYNOPSIS
    T4-5 ③ — 조회 SCARD pipeline 토글 측정. pool·tx 를 고정하고 Pipeline off/on 으로 L3(조회 폭주)를 반복 실행한다.
.DESCRIPTION
    SCARD 를 직렬 N회 → 파이프라인 1회 왕복으로 묶었을 때(RTT×N → RTT×1) 조회 p95·포화점 변화를 본다.
    각 토글은 Run-Scenario-Container.ps1 -Pipeline <off|on> 으로 위임. 결과는 토글별 prefix 로 분리.
    첫 실행만 -Build(코드/yml 반영). 이후는 같은 이미지로 env 토글만.

    측정 매핑(docs/plans/Query_Path_Optimization_Plan.md §4):
      A3(독립)  : -RedisOutsideTx false  (baseline=tx안 위에서 pipeline 단독. SCARD 가 커넥션 점유 → 처리량 지렛대 가능)
      C3(누적)  : -RedisOutsideTx true   (②tx밖 위에서 pipeline 누적. 커넥션 점유는 이미 소거 → 주로 조회 p95 개선)
.PARAMETER PoolSize
    측정 중 고정할 HikariCP 풀 크기. 기본 10(C2 결론=pool 은 지렛대 아님 → 운영 기본값 유지).
.PARAMETER RedisOutsideTx
    측정 중 고정할 ② 토글. A3 는 'false'(tx안 baseline), C3 는 'true'(②tx밖 위 누적). 기본 'false'(A3).
.PARAMETER Pipeline
    스윕할 ③ 토글 목록. 기본 false,true (Before → After). 순서대로 실행.
.PARAMETER Iterations
    토글당 L3 회차 수. 기본 3(일관성 확인).
.PARAMETER Hold
    L3 유지 구간(회차당 길이). 기본 '90s' — p95 는 과표본이라 단축해도 점추정 안정, 시간을 회차 반복에 투자.
.PARAMETER Limit
    조회 페이지 크기(=요청당 SCARD N). 기본 50 — pipeline 효과(RTT×N→×1)는 N 에 비례하므로 시드 상한(50)까지
    키워 효과크기를 가시화한다. N=8(기본)에선 효과가 측정 바닥 이하라 의미 없음.
#>
[CmdletBinding()]
param(
    [int]$PoolSize = 10,
    [ValidateSet('true','false')][string]$RedisOutsideTx = 'false',
    [ValidateSet('true','false')][string[]]$Pipeline = @('false', 'true'),
    [int]$Iterations = 3,
    [string]$Hold = '90s',
    [int]$Limit = 50
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot
try {
    $txLabel = if ($RedisOutsideTx -eq 'true') { 'txon' } else { 'txoff' }
    $nLabel = if ($Limit -gt 0) { $Limit } else { 8 }  # Limit=0 이면 서버 기본 N=8.
    $first = $true
    foreach ($pl in $Pipeline) {
        $label = if ($pl -eq 'true') { 'on' } else { 'off' }
        Write-Host "`n=========== POOL=$PoolSize · redisOutsideTx=$RedisOutsideTx · N=$nLabel · pipeline=$pl ($Iterations 회, hold=$Hold) ===========" -ForegroundColor Magenta
        $args = @{
            Scenario       = 'load-tests/scenarios/L3_list_query.js'
            ResultPrefix   = "L3_pool${PoolSize}_${txLabel}_n${nLabel}_pipe${label}"
            Iterations     = $Iterations
            AdmissionMax   = 2000        # 조회 부하 — 입장 제어 우회
            DbPoolSize     = $PoolSize
            RedisOutsideTx = $RedisOutsideTx
            Pipeline       = $pl
            Hold           = $Hold
            Limit          = $Limit
        }
        # 첫 토글의 첫 실행만 재빌드(코드/yml 을 이미지에 반영). 이후는 env 토글만.
        if ($first) { $args['Build'] = $true; $first = $false }
        & "$PSScriptRoot/Run-Scenario-Container.ps1" @args
    }
    Write-Host "`n전체 pipeline sweep 종료. 결과: load-tests/results/L3_pool${PoolSize}_${txLabel}_n${nLabel}_pipe*_container_run_*.txt" -ForegroundColor Green
}
finally {
    Pop-Location
}
