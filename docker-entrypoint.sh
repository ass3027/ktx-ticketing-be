#!/bin/sh
# JDK 25 AOT cache (JEP 483) 활용 부팅.
# 캐시는 named volume(/app/aot)에 두어 컨테이너 재생성(force-recreate)·재배포에도 보존한다.
# → 최초 1회만 dump(~10s), 이후 부팅은 mmap 로드로 단축. (컨테이너 쓰기 레이어에 두면
#   force-recreate 마다 사라져 매번 재 dump 되므로 volume 영속화가 필수.)
#
# 무효화: jar 가 캐시보다 최신이면(코드 변경 후 재빌드) 캐시를 재생성한다. 동일 jar 면 재사용.
#
# dump 단계는 spring.context.exit=onRefresh 로 컨텍스트 refresh 까지만 도달하고 종료한다.
# ApplicationRunner (DataInitializer, AvailPoolWarmup) 는 refresh 이후 단계라 dump 중엔 실행되지
# 않아 안전 (시드/워밍업 부수효과 없음). JPA EntityManagerFactory 는 refresh 단계에 만들어지므로
# mysql/redis 가 떠 있어야 dump 가 성공한다 — docker compose depends_on 으로 보장된다.
#
# dump 실패해도 본 부팅은 정상 진행 (캐시 없이 출발).

set -e

CACHE_DIR=/app/aot
CACHE="$CACHE_DIR/app.aot"
JAR=/app/app.jar

# JVM tz 를 KST 로 명시 고정(B-1) — naive LocalDateTime 의 now()/저장값이 MySQL·JDBC(Asia/Seoul)와
# 같은 tz 를 쓰게 한다. TZ env 만으론 base 이미지/JDK 에 따라 안 먹을 수 있어 -Duser.timezone 으로 강제.
# dump·본 부팅 양쪽에 동일 적용해야 시각 출처가 일관된다.
TZ_OPT="-Duser.timezone=Asia/Seoul"

mkdir -p "$CACHE_DIR"

# 재 dump 필요 판단: 캐시 없음 OR jar 가 캐시보다 최신(코드 변경).
NEED_DUMP=0
if [ ! -f "$CACHE" ]; then
  NEED_DUMP=1
  echo "[entrypoint] AOT cache 없음 → dump 시작 (최초 1회)"
elif [ "$JAR" -nt "$CACHE" ]; then
  NEED_DUMP=1
  echo "[entrypoint] jar 가 캐시보다 최신 → AOT cache 무효화·재 dump"
fi

if [ "$NEED_DUMP" -eq 1 ]; then
  if java $TZ_OPT -XX:AOTCacheOutput="$CACHE" \
          -Dspring.context.exit=onRefresh \
          -jar "$JAR"; then
    echo "[entrypoint] AOT cache dump 완료: $CACHE ($(du -h "$CACHE" | cut -f1))"
  else
    echo "[entrypoint] AOT cache dump 실패 — 캐시 없이 부팅 진행"
    rm -f "$CACHE"
  fi
else
  echo "[entrypoint] AOT cache 재사용: $CACHE"
fi

if [ -f "$CACHE" ]; then
  exec java $TZ_OPT -XX:AOTCache="$CACHE" -jar "$JAR"
else
  exec java $TZ_OPT -jar "$JAR"
fi
