#!/usr/bin/env bash
# 부하 테스트 전 DB/Redis 초기화
# 사용법: bash load-tests/seed/reset.sh
# 앱을 재기동해야 DataInitializer 가 시드를 다시 넣는다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-ktx_ticketing}"
DB_USER="${DB_USER:-ktx}"
DB_PASS="${DB_PASS:-ktx1234}"

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"

echo "=== DB 초기화 ==="
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" < "$SCRIPT_DIR/reset.sql"
echo "DB TRUNCATE 완료"

echo "=== Redis 초기화 ==="
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" FLUSHDB
echo "Redis FLUSHDB 완료"

echo ""
echo "완료. 앱을 재기동하면 DataInitializer 가 시드 데이터를 자동으로 재적재합니다."
echo "  docker compose restart app"
echo "  또는 IDE 에서 앱 재시작"
