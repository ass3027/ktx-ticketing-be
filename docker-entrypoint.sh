#!/bin/sh
# JDK 25 AOT cache (JEP 483) 활용 부팅.
# 첫 컨테이너 시작 시 dump 1회 → 이후 부팅마다 mmap 로드로 단축.
#
# dump 단계는 spring.context.exit=onRefresh 로 컨텍스트 refresh 까지만 도달하고 종료한다.
# ApplicationRunner (DataInitializer, AvailPoolWarmup) 는 refresh 이후 단계라 dump 중엔 실행되지
# 않아 안전 (시드/워밍업 부수효과 없음). JPA EntityManagerFactory 는 refresh 단계에 만들어지므로
# mysql/redis 가 떠 있어야 dump 가 성공한다 — docker compose depends_on 으로 보장된다.
#
# dump 실패해도 본 부팅은 정상 진행 (캐시 없이 출발).

set -e

CACHE=/app/app.aot
JAR=/app/app.jar

if [ ! -f "$CACHE" ]; then
  echo "[entrypoint] AOT cache 없음 → dump 시작 (1회)"
  if java -XX:AOTCacheOutput="$CACHE" \
          -Dspring.context.exit=onRefresh \
          -jar "$JAR"; then
    echo "[entrypoint] AOT cache dump 완료: $CACHE ($(du -h "$CACHE" | cut -f1))"
  else
    echo "[entrypoint] AOT cache dump 실패 — 캐시 없이 부팅 진행"
    rm -f "$CACHE"
  fi
fi

if [ -f "$CACHE" ]; then
  exec java -XX:AOTCache="$CACHE" -jar "$JAR"
else
  exec java -jar "$JAR"
fi
