#requires -Version 7
<#
.SYNOPSIS
    부하 테스트 후 정합성 검증 — DB 쿼리(post_run_check.sql) + Redis SCARD.
.DESCRIPTION
    docker compose exec 경유 mysql/redis-cli 사용 (호스트 설치 의존 없음).
    SQL 의 :seat_inventory_id / :schedule_id 바인딩은 mysql CLI 가 지원 안 하므로
    SET 변수 + 치환으로 직접 주입한다.
.PARAMETER ScheduleId
    검증 대상 스케줄 ID. 기본 1 (L1 시드 첫 스케줄).
.PARAMETER SeatInventoryId
    검증 대상 좌석 재고 ID. 기본 1 (L1 인기 좌석).
.PARAMETER OutputPath
    결과를 파일로 저장 (선택). 지정하면 stdout 과 동시에 파일에 저장.
.EXAMPLE
    pwsh load-tests/scripts/Invoke-PostRunCheck.ps1
.EXAMPLE
    pwsh load-tests/scripts/Invoke-PostRunCheck.ps1 -OutputPath load-tests/results/L1_check_1.txt
#>
[CmdletBinding()]
param(
    [int]$ScheduleId = 1,
    [int]$SeatInventoryId = 1,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# SQL 의 한글 라벨이 docker/mysql 파이프를 거치며 깨지지 않도록 UTF-8 고정.
# $OutputEncoding   : PowerShell → 네이티브(docker) stdin 으로 보낼 때 바이트 인코딩.
# [Console]::OutputEncoding : 네이티브 stdout 을 PowerShell 이 문자열로 디코딩할 때 사용.
# 둘 다 UTF-8(no BOM) 으로 두고, mysql 자체도 --default-character-set=utf8mb4 로 받는다.
$prevOutputEncoding = $OutputEncoding
$prevConsoleEncoding = [Console]::OutputEncoding
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

Push-Location $repoRoot
try {
    # post_run_check.sql 에 :변수 가 들어있어 직접 SET 으로 풀어쓴다 (L1 합격 단언 위주).
    $sql = @"
SET @sched := $ScheduleId;
SET @seat  := $SeatInventoryId;

SELECT '① seat 의 status별 건수 (L1: HELD=1, 그 외 0)' AS check_;
SELECT status, COUNT(*) AS cnt
FROM reservation
WHERE seat_inventory_id = @seat
GROUP BY status;

SELECT '② 동일 좌석 중복 HELD/CONFIRMED (L1: 결과 0행)' AS check_;
SELECT seat_inventory_id, status, COUNT(*) AS cnt
FROM reservation
WHERE status IN ('HELD', 'CONFIRMED')
GROUP BY seat_inventory_id, status
HAVING COUNT(*) > 1;

SELECT '④ 스케줄 AVAILABLE 좌석 수 (L1: 999 = 총 1,000 - 1)' AS check_;
SELECT COUNT(*) AS available_count
FROM seat_inventory
WHERE schedule_id = @sched AND status = 'AVAILABLE';

SELECT '⑤ HELD 만료 잔재 (L1: 0)' AS check_;
SELECT COUNT(*) AS expired_held_count
FROM reservation
WHERE status = 'HELD' AND expires_at < NOW();
"@

    $output = @()
    $output += '=== DB 정합성 ==='
    $output += ($sql | docker compose exec -T mysql mysql -uktx -pktx1234 --default-character-set=utf8mb4 -t ktx_ticketing)
    if ($LASTEXITCODE -ne 0) { throw "mysql 정합성 쿼리 실패 (exit $LASTEXITCODE)" }

    $output += ''
    $output += "=== Redis avail:$ScheduleId ==="
    $scard = docker compose exec -T redis redis-cli SCARD "avail:$ScheduleId"
    if ($LASTEXITCODE -ne 0) { throw "redis SCARD 실패 (exit $LASTEXITCODE)" }
    $output += "SCARD avail:${ScheduleId} = $($scard.Trim())  (L1 합격: 999)"

    $output | ForEach-Object { Write-Host $_ }
    if ($OutputPath) {
        $dir = Split-Path -Parent $OutputPath
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
        $output | Set-Content -Path $OutputPath -Encoding UTF8
        Write-Host "결과 저장: $OutputPath" -ForegroundColor Green
    }
}
finally {
    $OutputEncoding = $prevOutputEncoding
    [Console]::OutputEncoding = $prevConsoleEncoding
    Pop-Location
}
