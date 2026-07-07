#requires -Version 7
<#
.SYNOPSIS
    T4-5 ④(= 실험 E3) — 조회 단기 캐시 on/off 측정. 같은 ②③ 조합에서 CacheEnabled off/on 으로 L3(조회 폭주)를 반복 실행한다.
.DESCRIPTION
    캐시 토글만 바꿔 L3 를 돌려 SCARD→GET rate 전환·조회 p95·포화점 변화를 본다.
    각 토글은 Run-Scenario-Container.ps1 -CacheEnabled <off|on> 으로 위임. 결과는 토글별 prefix 로 분리.
    첫 실행만 -Build(코드/yml 반영). 이후는 같은 이미지로 env 토글만.

    측정 매핑(docs/plans/Query_Path_Optimization_Plan.md §4):
      A4(독립) : ②③ 모두 off (off=baseline vs on=캐시 단독 효과 = E3 after)
      C4(누적) : ②③ 확정 조합 위에 (off=C3 재측정 vs on=전부 on 최종 도달점)

    E3 매핑(§4.4): Before(cache off)=§1 L3 실측(이미 확보) / After(cache on)=이 스윕의 on 회차.
.PARAMETER RedisOutsideTx
    측정 중 고정할 ② 토글. A4=false(baseline), C4=누적 확정값(②는 tx밖=true 가 근본 지렛대, §7 로그).
.PARAMETER Pipeline
    측정 중 고정할 ③ 토글. 기본 false(N≈8 운영점에선 레버 아님 — §7 ③ 결론).
.PARAMETER PoolSize
    측정 중 고정할 HikariCP 풀 크기. 기본 10(운영값 유지 — ①②는 상호 대체재, §7 C2 로그).
.PARAMETER CacheEnabled
    스윕할 캐시 토글 목록. 기본 false,true (Before → After). 순서대로 실행.
.PARAMETER Iterations
    토글당 L3 회차 수. 기본 3(일관성 확인).
#>
[CmdletBinding()]
param(
    [ValidateSet('true','false')][string]$RedisOutsideTx = 'false',
    [ValidateSet('true','false')][string]$Pipeline = 'false',
    [int]$PoolSize = 10,
    [ValidateSet('true','false')][string[]]$CacheEnabled = @('false', 'true'),
    [int]$Iterations = 3
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot
try {
    $first = $true
    foreach ($qc in $CacheEnabled) {
        $label = if ($qc -eq 'true') { 'on' } else { 'off' }
        Write-Host "`n=========== POOL=$PoolSize · redisOutsideTx=$RedisOutsideTx · pipeline=$Pipeline · cache=$qc ($Iterations 회) ===========" -ForegroundColor Magenta
        $args = @{
            Scenario       = 'load-tests/scenarios/L3_list_query.js'
            ResultPrefix   = "L3_pool${PoolSize}_tx${RedisOutsideTx}_pl${Pipeline}_cache${label}"
            Iterations     = $Iterations
            AdmissionMax   = 2000        # 조회 부하 — 입장 제어 우회
            DbPoolSize     = $PoolSize
            RedisOutsideTx = $RedisOutsideTx
            Pipeline       = $Pipeline
            CacheEnabled   = $qc
        }
        # 첫 토글의 첫 실행만 재빌드(코드/yml 을 이미지에 반영). 이후는 env 토글만.
        if ($first) { $args['Build'] = $true; $first = $false }
        & "$PSScriptRoot/Run-Scenario-Container.ps1" @args
    }
    Write-Host "`n전체 cache sweep 종료. 결과: load-tests/results/L3_pool${PoolSize}_tx${RedisOutsideTx}_pl${Pipeline}_cache*_container_run_*.txt" -ForegroundColor Green
}
finally {
    Pop-Location
}
