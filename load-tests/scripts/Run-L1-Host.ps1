#requires -Version 7
<#
.SYNOPSIS
    T4-3 — L1 단일 좌석 동시 경쟁(정합성) 측정 — 호스트 JVM 실행판.
.DESCRIPTION
    Run-L1.ps1 의 변형. 앱을 Docker 컨테이너가 아니라 호스트 JVM(java -jar)으로 띄운다.

    배경: Windows + Docker Desktop 에서 컨테이너 포트(8080)는 포트 프록시(NAT)를 거치는데,
    1,000 VU 동시 SYN burst 의 60~78%가 그 NAT 포화로 connection refused(RST) 된다
    (실측: 컨테이너 631 vs 호스트 95, accept-count 튜닝 무효, host networking 은 Windows 부적합).
    NAT 를 우회하려고 앱만 호스트 JVM 으로 실행해 1,000 동시 경쟁이 실제로 도달하게 한다.

    mysql/redis 는 Docker 유지(호스트 매핑 포트 127.0.0.1:3308 / :6379 로 접속).
    매 회: TRUNCATE+FLUSHDB → 호스트 JVM 재기동(재시드+워밍업) → health 대기
          → k6 run → Invoke-PostRunCheck.

    선행: bootJar 빌드 필요 — `JAVA_HOME=<JDK25>; ./gradlew bootJar -x test`.
          Docker app 컨테이너는 이 스크립트가 자동 중지한다(8080 충돌 회피).
.PARAMETER Iterations
    반복 회수. 기본 3.
.PARAMETER Jdk25Home
    JDK 25 홈. 기본 $env:USERPROFILE\.jdks\openjdk-25.0.2.
.PARAMETER JarPath
    실행할 bootJar. 기본 build/libs/ktx-ticketing-be-0.0.1-SNAPSHOT.jar.
.EXAMPLE
    pwsh load-tests/scripts/Run-L1-Host.ps1 -Iterations 3
#>
[CmdletBinding()]
param(
    [int]$Iterations = 3,
    [int]$ScheduleId = 1,
    [int]$SeatInventoryId = 1,
    [int]$HealthWaitSeconds = 120,
    [string]$Jdk25Home = "$env:USERPROFILE\.jdks\openjdk-25.0.2",
    [string]$JarPath = 'build/libs/ktx-ticketing-be-0.0.1-SNAPSHOT.jar'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot

$javaExe = Join-Path $Jdk25Home 'bin\java.exe'
$appProc = $null

function Stop-HostApp {
    # 8080 을 점유한 java 프로세스를 종료한다 (호스트 JVM 정리).
    $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
        # 포트 해제까지 짧게 폴링
        for ($w = 0; $w -lt 20; $w++) {
            Start-Sleep -Milliseconds 300
            if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) { break }
        }
    }
}

try {
    if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { throw 'k6 명령을 찾을 수 없다.' }
    if (-not (Test-Path $javaExe)) { throw "JDK 25 java 없음: $javaExe" }
    if (-not (Test-Path $JarPath)) { throw "bootJar 없음: $JarPath — './gradlew bootJar -x test' 먼저." }

    $resultsDir = 'load-tests/results'
    if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Force $resultsDir | Out-Null }

    Write-Host '=== Docker app 컨테이너 중지 (8080 충돌 회피) ===' -ForegroundColor Cyan
    docker compose stop app 2>&1 | Out-Null
    Stop-HostApp

    $env:K6_WEB_DASHBOARD = 'false'
    $env:SCHEDULE_ID = "$ScheduleId"
    $env:SEAT_INVENTORY_ID = "$SeatInventoryId"

    # 호스트 JVM 이 쓸 접속 정보 (Docker 매핑 포트)
    $appEnv = @{
        JAVA_HOME = $Jdk25Home
        DB_HOST = '127.0.0.1'; DB_PORT = '3308'
        REDIS_HOST = '127.0.0.1'; REDIS_PORT = '6379'
        BOOKING_ADMISSION_MAX_ACTIVE = '2000'
        SPRING_PROFILES_ACTIVE = 'local'
    }

    for ($i = 1; $i -le $Iterations; $i++) {
        Write-Host "`n############### L1(Host) 회차 $i / $Iterations ###############" -ForegroundColor Cyan

        Write-Host '=== DB TRUNCATE ===' -ForegroundColor Cyan
        Get-Content load-tests/seed/reset.sql -Raw | docker compose exec -T mysql mysql -uktx -pktx1234 ktx_ticketing
        if ($LASTEXITCODE -ne 0) { throw "mysql reset 실패 (exit $LASTEXITCODE)" }
        Write-Host '=== Redis FLUSHDB ===' -ForegroundColor Cyan
        docker compose exec -T redis redis-cli FLUSHDB | Out-Null

        Write-Host '=== 호스트 JVM 재기동 ===' -ForegroundColor Cyan
        Stop-HostApp
        foreach ($k in $appEnv.Keys) { Set-Item "env:$k" $appEnv[$k] }
        $appLog = Join-Path $resultsDir "L1_host_app_$i.log"
        $appProc = Start-Process -FilePath $javaExe -ArgumentList '-jar', $JarPath `
            -RedirectStandardOutput $appLog -RedirectStandardError "$appLog.err" `
            -PassThru -WindowStyle Hidden

        Write-Host "=== health UP 대기 (최대 ${HealthWaitSeconds}s) ===" -ForegroundColor Cyan
        $deadline = (Get-Date).AddSeconds($HealthWaitSeconds)
        $up = $false
        while ((Get-Date) -lt $deadline) {
            try {
                $h = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 2 -ErrorAction Stop
                if ($h.status -eq 'UP') { $up = $true; break }
            } catch {}
            Start-Sleep -Seconds 1
        }
        if (-not $up) { throw "health UP 시간초과 — $appLog 확인" }
        Write-Host 'health=UP' -ForegroundColor Green

        $runLog = Join-Path $resultsDir "L1_host_run_$i.txt"
        Write-Host "k6 실행 → $runLog" -ForegroundColor Cyan
        k6 run load-tests/scenarios/L1_single_seat_race.js 2>&1 | Tee-Object -FilePath $runLog | Out-Null
        $k6Exit = $LASTEXITCODE

        $checkLog = Join-Path $resultsDir "L1_host_run_$i.check.txt"
        & "$PSScriptRoot/Invoke-PostRunCheck.ps1" -ScheduleId $ScheduleId -SeatInventoryId $SeatInventoryId -OutputPath $checkLog

        # 회차 요약 (refused / 정합성 한 줄)
        $clean = (Get-Content $runLog -Raw) -replace '\x1b\[[0-9;]*m',''
        $refused = ([regex]::Matches($clean, 'actively refused')).Count
        Write-Host "회차 $i : refused=$refused, k6Exit=$k6Exit" -ForegroundColor $(if ($k6Exit -eq 0) { 'Green' } else { 'Yellow' })
    }

    Write-Host "`n전체 회차 종료. 결과: $resultsDir/L1_host_run_*.{txt,check.txt}" -ForegroundColor Green
}
finally {
    Write-Host '=== 정리: 호스트 JVM 종료 ===' -ForegroundColor DarkGray
    Stop-HostApp
    Write-Host 'Docker app 복구하려면: docker compose up -d app' -ForegroundColor DarkGray
    Pop-Location
}
