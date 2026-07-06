#requires -Version 7
<#
.SYNOPSIS
    L1~L6 부하 시나리오 — k6 컨테이너 실행판 (시나리오 무관 일반화).
.DESCRIPTION
    k6 를 app 과 같은 docker 네트워크에서 실행한다(`docker-compose.k6.yml`, BASE_URL=http://app:8080).
    컨테이너↔컨테이너 통신이라 Windows Docker Desktop 의 포트 프록시(NAT) 와 호스트 ephemeral
    포트/TIME_WAIT 압박을 모두 우회 → refused=0 으로 진짜 N 동시 부하를 측정한다.
    (시나리오 경로/결과 prefix 를 파라미터화한 L1~L6 공용 러너.)

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
.PARAMETER AdmissionMax
    app 의 BOOKING_ADMISSION_MAX_ACTIVE(K). 기본 2000=입장 제어 우회(L1/L2).
    L4 처럼 입장 제어를 발동시켜야 하면 100(운영값) 을 준다.
.PARAMETER ExpiryBatchSideEffects
    만료 sweep 부수효과 발사 방식(T4-13). 'true'(기본·After)=scheduleId별 배치 SADD/DECRBY,
    'false'(Before)=건별 직렬 2N RTT. L6 sweep 병목 Before/After 측정 시 토글.
.PARAMETER ExpiryBatchSize
    sweep 1회 최대 처리 건수(T4-13). 기본 1000(After). Before 재현 시 100.
.PARAMETER ExpirySweepInterval
    sweep 실행 주기(T4-13). 기본 2s(After). Before 재현 시 30s.
.PARAMETER Build
    첫 회차에서 app 이미지를 --build 로 재빌드해 코드 변경을 반영한다(Dockerfile=소스 빌드).
    코드를 바꾼 뒤 측정할 때 필수 — 없으면 옛 이미지로 돌아 변경이 반영되지 않는다(측정 무효).
.PARAMETER DbPoolSize
    HikariCP 커넥션 풀 크기(T4-5 ①). 기본 10(Before). L3 조회 최적화에서 pool 상향(20/30/50) 효과를
    측정할 때 토글. env→yml 바인딩 관통 여부를 actuator hikaricp.connections.max 로 검증한다.
.PARAMETER RedisOutsideTx
    조회 SCARD 를 DB 트랜잭션 밖에서 수행할지(T4-5 ②). 'false'(기본·Before)=SCARD tx 안,
    'true'=tx 밖(커넥션이 Redis 왕복 미포함 → usage 단축). 컨테이너에 실제 주입됐는지 env 로 검증한다
    (효과는 actuator 아닌 부하 지표 usage/포화점으로 관측 — 커스텀 프로퍼티라 전용 미터가 없다).
.PARAMETER Pipeline
    조회 SCARD 를 파이프라인 1회 왕복으로 묶을지(T4-5 ③). 'false'(기본·Before)=직렬 N회,
    'true'=배치 1회(RTT×N → RTT×1). ②와 독립 토글. 컨테이너에 실제 주입됐는지 env 로 검증한다
    (커스텀 프로퍼티라 전용 미터 없음 — 효과는 부하 지표 조회 p95/포화점으로 관측).
.PARAMETER Dashboard
    k6 web dashboard 를 켜고 시계열 차트를 HTML 로 export 한다(results/{prefix}_dashboard.html).
    soak(L6) 의 응답시간 우상향(누수) 판정처럼 시계열 추세가 필요한 시나리오에서 켠다.
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
    [int]$AdmissionMax = 2000,
    [ValidateSet('true','false')][string]$ExpiryBatchSideEffects = 'true',
    [int]$ExpiryBatchSize = 1000,
    [string]$ExpirySweepInterval = '2s',
    [int]$DbPoolSize = 10,
    [ValidateSet('true','false')][string]$RedisOutsideTx = 'false',
    [ValidateSet('true','false')][string]$Pipeline = 'false',
    [switch]$PostRunCheck,
    [switch]$Build,
    [switch]$Dashboard
)

# ── 회차 단계 함수 ──────────────────────────────────────────────────────────

function Reset-SeedState {
    # DB TRUNCATE + Redis FLUSHDB. 스키마는 안 건드림 — 재시드는 app 재기동이 담당.
    Write-Host '=== DB TRUNCATE ===' -ForegroundColor Cyan
    Get-Content load-tests/seed/reset.sql -Raw | docker compose exec -T mysql mysql -uktx -pktx1234 ktx_ticketing
    if ($LASTEXITCODE -ne 0) { throw "mysql reset 실패 (exit $LASTEXITCODE)" }
    Write-Host '=== Redis FLUSHDB ===' -ForegroundColor Cyan
    docker compose exec -T redis redis-cli FLUSHDB | Out-Null
}

function Restart-App {
    # app 재생성 후 healthy 대기 + admission(K) 주입값 보장.
    # reset 후 seed/워밍업(DataInitializer/AvailPoolWarmup, 부팅 1회)을 다시 태운다.
    # AOT 캐시는 named volume 에 보존돼 재 dump 없이 빠르게 뜬다.
    param([string[]]$Compose, [int]$AdmissionMax, [int]$HealthWaitSeconds, [bool]$Build)

    # Dockerfile 은 소스에서 빌드(멀티스테이지)하므로, 코드 변경을 반영하려면 --build 가 필요하다.
    # --force-recreate 만으로는 기존 이미지로 컨테이너만 새로 만들어 옛 코드가 돈다(측정 무효).
    # 캐시가 있어 변경 없으면 빠르므로 -Build 회차에 한해 재빌드한다.
    $recreate = if ($Build) { '--build' } else { '--force-recreate' }
    Write-Host "=== app 재생성 ($recreate, admission=$AdmissionMax env) ===" -ForegroundColor Cyan
    docker compose @Compose up -d $recreate app 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "app 재기동 실패 (exit $LASTEXITCODE)" }

    Write-Host "=== app healthy 대기 (최대 ${HealthWaitSeconds}s) ===" -ForegroundColor Cyan
    $deadline = (Get-Date).AddSeconds($HealthWaitSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((docker inspect ktx-ticketing-be-app-1 --format '{{.State.Health.Status}}' 2>$null) -eq 'healthy') {
            $adm = (docker compose exec -T app sh -c 'echo $BOOKING_ADMISSION_MAX_ACTIVE' 2>$null).Trim()
            if ($adm -ne "$AdmissionMax") { throw "admission 보장 실패: BOOKING_ADMISSION_MAX_ACTIVE=$adm (기대 $AdmissionMax)" }
            # sweep 설정도 컨테이너에 실제 주입됐는지 확인(T4-13 Before/After 가 옛 설정으로 도는 측정 무효 방지).
            $se = (docker compose exec -T app sh -c 'echo $BOOKING_EXPIRY_BATCH_SIDE_EFFECTS' 2>$null).Trim()
            $bs = (docker compose exec -T app sh -c 'echo $BOOKING_EXPIRY_BATCH_SIZE' 2>$null).Trim()
            $si = (docker compose exec -T app sh -c 'echo $BOOKING_EXPIRY_SWEEP_INTERVAL' 2>$null).Trim()
            if ($se -ne $env:BOOKING_EXPIRY_BATCH_SIDE_EFFECTS) { throw "sweep 보장 실패: BATCH_SIDE_EFFECTS=$se (기대 $env:BOOKING_EXPIRY_BATCH_SIDE_EFFECTS)" }
            if ($bs -ne $env:BOOKING_EXPIRY_BATCH_SIZE) { throw "sweep 보장 실패: BATCH_SIZE=$bs (기대 $env:BOOKING_EXPIRY_BATCH_SIZE)" }
            if ($si -ne $env:BOOKING_EXPIRY_SWEEP_INTERVAL) { throw "sweep 보장 실패: SWEEP_INTERVAL=$si (기대 $env:BOOKING_EXPIRY_SWEEP_INTERVAL)" }
            # DB 풀 크기는 env 뿐 아니라 실제 HikariCP max 로 확인(T4-5 ①이 옛 풀로 도는 측정 무효 방지).
            # env→yml 바인딩까지 관통했는지 actuator 로 관측 — env 만 맞고 바인딩 틀리면 못 잡는다.
            $pool = (docker compose exec -T app sh -c 'echo $DB_POOL_SIZE' 2>$null).Trim()
            if ($pool -ne "$DbPoolSize") { throw "pool 보장 실패: DB_POOL_SIZE=$pool (기대 $DbPoolSize)" }
            $hikariMax = (curl.exe -s "http://localhost:8080/actuator/metrics/hikaricp.connections.max" 2>$null |
                Select-String -Pattern '"value":([0-9.]+)').Matches.Groups[1].Value
            if ([int]$hikariMax -ne $DbPoolSize) { throw "HikariCP max 보장 실패: actuator=$hikariMax (기대 $DbPoolSize)" }
            # 조회 tx-밖 토글도 컨테이너에 실제 주입됐는지 확인(T4-5 ② off/on 이 옛 값으로 도는 측정 무효 방지).
            # 커스텀 프로퍼티라 actuator 미터가 없어 env 로만 검증 — 효과는 부하 지표 usage 로 관측한다.
            $rtx = (docker compose exec -T app sh -c 'echo $BOOKING_QUERY_REDIS_OUTSIDE_TX' 2>$null).Trim()
            if ($rtx -ne $env:BOOKING_QUERY_REDIS_OUTSIDE_TX) { throw "redisOutsideTx 보장 실패: BOOKING_QUERY_REDIS_OUTSIDE_TX=$rtx (기대 $env:BOOKING_QUERY_REDIS_OUTSIDE_TX)" }
            # pipeline 토글도 컨테이너에 실제 주입됐는지 확인(T4-5 ③ off/on 이 옛 값으로 도는 측정 무효 방지).
            $pl = (docker compose exec -T app sh -c 'echo $BOOKING_QUERY_PIPELINE' 2>$null).Trim()
            if ($pl -ne $env:BOOKING_QUERY_PIPELINE) { throw "pipeline 보장 실패: BOOKING_QUERY_PIPELINE=$pl (기대 $env:BOOKING_QUERY_PIPELINE)" }
            Write-Host "app healthy, admission=$adm, sweep(sideEffects=$se size=$bs interval=$si), hikariMax=$hikariMax, redisOutsideTx=$rtx, pipeline=$pl" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "app healthy 시간초과"
}

function Invoke-K6Run {
    # 컨테이너 k6 실행. 결과 종료코드를 반환한다.
    # Tee-Object 로 파일 저장과 동시에 Out-Host 로 콘솔에 표시(k6 기본 출력). Out-Null 금지.
    # 함수 안에서는 Tee 의 파이프 출력이 반환값으로 새므로 Out-Host 로 흡수 → return 만 출력.
    param([string[]]$Compose, [string]$ContainerScenario, [int]$ScheduleId, [int]$SeatInventoryId,
          [string]$RunLog, [string]$ResultPrefix, [string[]]$DashboardEnv = @())

    Write-Host "k6(컨테이너) 실행 → $RunLog" -ForegroundColor Cyan
    # RESULT_PREFIX 를 시나리오에 넘겨 handleSummary 의 summary 파일명을 분기(연속 회차 덮어쓰기 방지).
    docker compose @Compose run --rm `
        -e SCHEDULE_ID=$ScheduleId -e SEAT_INVENTORY_ID=$SeatInventoryId -e RESULT_PREFIX=$ResultPrefix `
        @DashboardEnv `
        k6 run $ContainerScenario 2>&1 |
        Tee-Object -FilePath $RunLog | Out-Host
    return $LASTEXITCODE
}

function Write-RunSummary {
    # 회차 로그에서 refused/http_reqs 를 뽑아 한 줄 요약. ANSI 색상코드는 제거 후 파싱.
    param([string]$RunLog, [int]$Iteration, [int]$K6Exit)

    $clean = (Get-Content $RunLog -Raw) -replace '\x1b\[[0-9;]*m',''
    $refused = ([regex]::Matches($clean, 'actively refused|connection refused|dial tcp')).Count
    $reqs = if ($clean -match 'http_reqs[\.\s]+:\s*(\d+)') { $matches[1] } else { '?' }
    $color = if ($K6Exit -eq 0) { 'Green' } else { 'Yellow' }
    Write-Host "회차 $Iteration : refused=$refused, http_reqs=$reqs, k6Exit=$K6Exit" -ForegroundColor $color
}

# ── 메인 ────────────────────────────────────────────────────────────────────

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot

if (-not $ResultPrefix) {
    # 시나리오 파일명 앞 토큰(L2_normal_flow → L2) 을 prefix 로.
    $ResultPrefix = (Split-Path -Leaf $Scenario) -replace '_.*$','' -replace '\.js$',''
}

# 단일 compose 조합 — app 재기동도 k6 실행도 동일 -f 세트. admission(K)은 env 로 주입.
# 기본 2000 = 입장 제어 우회(L1/L2 가 경쟁/처리량을 보려면 입장에 안 막혀야 함).
# L4(입장 제어 검증)처럼 K 를 발동시켜야 하면 -AdmissionMax 100 으로 정상값을 준다.
$compose = @('-f', 'docker-compose.yml', '-f', 'docker-compose.k6.yml')
$env:BOOKING_ADMISSION_MAX_ACTIVE = "$AdmissionMax"
# 만료 sweep 설정 주입(T4-13). compose 의 ${VAR:-default} 가 받음 → app 재기동 시 반영.
$env:BOOKING_EXPIRY_BATCH_SIDE_EFFECTS = $ExpiryBatchSideEffects
$env:BOOKING_EXPIRY_BATCH_SIZE = "$ExpiryBatchSize"
$env:BOOKING_EXPIRY_SWEEP_INTERVAL = $ExpirySweepInterval
# HikariCP 풀 크기 주입(T4-5 ①). compose base 의 ${DB_POOL_SIZE:-10} 가 받음 → app 재기동 시 반영.
$env:DB_POOL_SIZE = "$DbPoolSize"
# 조회 tx-밖 토글 주입(T4-5 ②). compose base 의 ${BOOKING_QUERY_REDIS_OUTSIDE_TX:-false} 가 받음.
$env:BOOKING_QUERY_REDIS_OUTSIDE_TX = $RedisOutsideTx
# 조회 pipeline 토글 주입(T4-5 ③). compose base 의 ${BOOKING_QUERY_PIPELINE:-false} 가 받음.
$env:BOOKING_QUERY_PIPELINE = $Pipeline
Write-Host "sweep 설정: batchSideEffects=$ExpiryBatchSideEffects batchSize=$ExpiryBatchSize interval=$ExpirySweepInterval, dbPool=$DbPoolSize, redisOutsideTx=$RedisOutsideTx, pipeline=$Pipeline" -ForegroundColor DarkCyan

# k6 컨테이너 안의 시나리오 경로 (load-tests 가 /work/load-tests 로 마운트됨).
$containerScenario = "/work/" + ($Scenario -replace '\\','/')

try {
    if (-not (Test-Path $Scenario)) { throw "시나리오 없음: $Scenario" }
    $resultsDir = 'load-tests/results'
    if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Force $resultsDir | Out-Null }

    for ($i = 1; $i -le $Iterations; $i++) {
        Write-Host "`n############### $ResultPrefix(Container) 회차 $i / $Iterations ###############" -ForegroundColor Cyan

        Reset-SeedState
        # 코드 변경 반영용 재빌드는 첫 회차에만(-Build 지정 시). 이후 회차는 같은 이미지로 recreate.
        Restart-App -Compose $compose -AdmissionMax $AdmissionMax -HealthWaitSeconds $HealthWaitSeconds `
            -Build ($Build -and $i -eq 1)

        $runLog = Join-Path $resultsDir "${ResultPrefix}_container_run_$i.txt"
        # -Dashboard 시 k6 web dashboard 를 켜고 시계열 차트를 HTML 로 export.
        # 컨테이너 경로 /work/load-tests/results 는 k6 compose 의 working_dir/volumes 로 호스트 results 에 매핑됨.
        $dashEnv = if ($Dashboard) {
            @('-e','K6_WEB_DASHBOARD=true',
              '-e',"K6_WEB_DASHBOARD_EXPORT=/work/load-tests/results/${ResultPrefix}_dashboard.html")
        } else { @() }
        $k6Exit = Invoke-K6Run -Compose $compose -ContainerScenario $containerScenario `
            -ScheduleId $ScheduleId -SeatInventoryId $SeatInventoryId -RunLog $runLog `
            -ResultPrefix $ResultPrefix -DashboardEnv $dashEnv

        if ($PostRunCheck) {
            $checkLog = Join-Path $resultsDir "${ResultPrefix}_container_run_$i.check.txt"
            & "$PSScriptRoot/Invoke-PostRunCheck.ps1" -ScheduleId $ScheduleId -SeatInventoryId $SeatInventoryId -OutputPath $checkLog
        }

        Write-RunSummary -RunLog $runLog -Iteration $i -K6Exit $k6Exit
    }

    Write-Host "`n전체 회차 종료. 결과: $resultsDir/${ResultPrefix}_container_run_*.txt" -ForegroundColor Green
}
finally {
    Pop-Location
}
