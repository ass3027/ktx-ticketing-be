> 상위 문서: `KTX_Ticketing_Architecture_and_Verification_Goals.md` (SLO S2·실험 E3)
> 출처: 2026-07-01 세션 — T4-5(L3 조회 폭주) 측정 중 규명한 조회 경로 포화 병목
> 상태: 계획 + Before 측정 완료(실측 근거 확보). 최적화 착수 전 계획 승인 대기.
> 연관: T4-5(L3)·T4-9(E3)·T3-2(SCARD 단일 소스 결정)·P4_Result.md

# 조회 경로 최적화 작업 기록 (Query Path Optimization)

## 0. 한 줄 요약

운행편 리스트 조회(`GET /api/schedules`)가 **캐시 없이 페이지 편수만큼 SCARD 를 트랜잭션 안에서
직렬 왕복**하는 구조라, 순수 읽기 부하(L3, 목표 3,000 TPS)에서 **~800 TPS 에서 포화**하고 조회
p95 가 SLO(200ms)를 크게 벗어난다(9.5~12s). 포트폴리오 목적상 **여러 최적화 기법을 각각 측정**한다:
**① DB pool size 상향 → ② Redis(SCARD)를 트랜잭션 밖으로 → ③ Redis pipeline → ④ 조회 단기 캐시**.
각 기법을 **토글(on/off)** 로 구현해 **독립 효과(각각 baseline 대비)** 와 **누적 효과(순차 적용)** 를
모두 Before/After 로 측정하고, 각 기법의 기여도를 개별 그래프로 남긴다.

---

## 1. Before 측정 — 무엇이 관측됐나 (T4-5 / L3)

### 1.1 부하 결과 (컨테이너 k6, 3회 일관)

목표 프로파일: 열린 루프(ramping-arrival-rate) 0→3,000 TPS ramp 1분 → 3분 유지 → 30s ramp-down.
`dropped_iterations==0` 게이트로 "생성기가 목표 도착률을 실제로 발사했는가"를 전제 단언.

| 지표 | SLO | 회차1 | 회차2 | 회차3 | 판정 |
|------|-----|------:|------:|------:|:----:|
| 조회 p95 (`type:list`) | ≤ 200ms | 9.46s | 9.65s | 11.98s | ❌ |
| dropped_iterations | == 0 | 458,973 | 456,830 | 487,091 | ❌ |
| 실효 http_reqs/s | (목표 3,000) | 781 | 783 | 686 | — |
| http_req_failed | < 1% | 0.00% | 0.00% | 0.00% | ✅ |
| checks (list 200) | > 0.99 | 100% | 100% | 100% | ✅ |

- **`Insufficient VUs, reached 5000 active VUs`** — 응답이 느려지자 in-flight 요청이 쌓여 maxVUs(5000)
  전량 소진 → 도착률 모델 붕괴.
- **dropped_iterations ~46만** — 목표 3,000 TPS 를 못 발사. 즉 관측 p95 는 부하 모델이 무너진
  상태의 값이라 **절대치가 아니라 "포화했다"는 사실**이 신호.
- **실효 처리량 ~780 TPS 포화** — 서버가 실제 소화한 상한. 이 숫자가 최적화의 Before 기준선.

### 1.2 병목 규명 (Prometheus 실측, 측정 시점 window)

| 지표 | 값 | 의미 |
|------|----|------|
| `hikaricp.connections.max` | **10** | 커넥션풀 상한 = **기본값(application.yml 미설정)** |
| `hikaricp_connections_active` (max) | **10** | 풀 상시 포화 |
| `hikaricp_connections_pending` (max) | **190** | 커넥션 못 얻고 대기하는 스레드 |
| `hikaricp_connections_acquire_seconds_max` | **3.92s** | 커넥션 획득 최대 대기 |
| acquire 평균 (sum/count) | **0.238s** | 요청당 평균 커넥션 대기만 238ms |
| `hikaricp_connections_usage_seconds_max` | **0.644s** | 커넥션 1개 최대 점유 시간 |
| `hikaricp_connections_timeout_total` | **0** | 30s 타임아웃엔 안 닿음 → http_req_failed 0% 와 일치 |

> Redis(Lettuce)·Tomcat 스레드 지표는 현재 미계측이라 Prometheus 에 없음(후속: 계측 추가 선택).

### 1.3 근본 원인 (2단 병목)

1. **주 병목 = HikariCP 풀 크기 10 (미튜닝).** 5,000 VU 가 커넥션 10개를 두고 경쟁 → 대기 큐 190,
   획득 대기 3.9s. 관측 p95 의 큰 몫이 순수 커넥션 획득 대기.
2. **증폭 원인 = 커넥션 점유 시간이 길다 (usage 644ms).** 조회가 `@Transactional(readOnly=true)` 안에서
   DB 쿼리 1회 + **SCARD 를 페이지 편수(기본 limit=8)만큼 직렬 왕복**하는 동안 커넥션을 계속 붙잡음.
   Redis 왕복이 DB 커넥션 회전율을 떨어뜨려, 풀이 작을수록 대기가 기하급수적으로 악화.

`open-in-view: false` 는 이미 맞게 설정돼 있으나(불필요한 커넥션 연장 없음), `@Transactional` 이
SCARD 루프 전체를 감싸는 게 핵심.

---

## 2. 코드 — 왜 이 구조인가 (의도된 naive 구현)

`src/main/java/com/ktx/ticketing/schedule/ScheduleQueryService.java:42`
```java
List<ScheduleResponse> items = page.stream()
        .map(s -> ScheduleResponse.from(s, preemption.availableCount(s.getId())))
        .toList();
```
`availableCount` → `RedisSetPreemption.availableCount` (`booking/RedisSetPreemption.java:96`)
```java
Long size = redis.opsForSet().size(key(scheduleId)); // SCARD avail:{scheduleId}
```

- 잔여석 단일 소스 = **스케줄별 Redis Set `avail:{schedule_id}`** (T3-2 결정: 별도 `remain:` 카운터
  미도입, Set 크기 재사용). 스케줄마다 **별개의 키**.
- 그래서 한 페이지 N편 = **서로 다른 N개 키 = N회 SCARD**. `.stream().map()` 은 순차라 **직렬 왕복**.
- **중복 호출이 아니라 서로 다른 키 순회**다. SCARD 명령 자체는 O(1)·µs 로 무겁지 않다 —
  비싼 건 **N회 직렬 네트워크 왕복(RTT×N)**, 그리고 그 왕복이 **DB 커넥션을 점유한 채** 일어나는 것.

코드 주석(`ScheduleQueryService.java:41`)에 이미 명시: *"파이프라인/캐시 최적화는 측정 후 E3 에서."*
→ 실수가 아니라 **"측정 후 최적화" 원칙**에 따라 남긴 Before 기준선. 지금 그 측정이 끝났다.

---

## 2.5 선행 과제 — Redis 명령 계측 복구 (②③ 측정의 전제)

②(tx 밖)·③(pipeline)의 효과는 "Redis 명령 지연·왕복 횟수"로 나타난다. 그런데 **현재 Redis 지표가
Prometheus/Grafana 에 하나도 수집되지 않는다**(확인: 2026-07-01). 이걸 먼저 복구하지 않으면
②③ 효과를 Redis 지표로 직접 못 보고 간접 추론만 가능하다.

### 무엇이 되고 무엇이 안 되나 (실측)
- **HikariCP: ✅ 수집됨** — Micrometer 자동계측. §1.2 병목 규명이 여기서 나옴.
- **Redis/Lettuce/Redisson: ❌ 전혀 안 됨** — actuator 원본·Prometheus 양쪽 `lettuce_command_*` 0건.
- **Grafana 대시보드**: `jvm.json` 하나뿐 → JVM 패널만, **Hikari·Redis 패널 없음**. (그래서 병목을
  Grafana 아닌 Prometheus API 직접 쿼리로 봤다.)

### 왜 안 되나 (원인 확정)
`RedisConfig` 는 Lettuce 계측용 `ClientResources`(`MicrometerCommandLatencyRecorder`)를 빈으로
등록하지만 **죽은 계측**이다. 이유:
```
StringRedisTemplate (SCARD 호출)
  └─ RedisConnectionFactory 주입
       └─ Redisson starter(4.4.0)가 RedissonConnectionFactory 로 대체  ← 여기
            └─ Lettuce 클라이언트 미사용 → ClientResources(Lettuce 전용) 우회
                 → lettuce_command_* 미터 생성 안 됨
```
**근거(소스 확정, 정황 아님)**: `redisson-spring-boot-starter:4.4.0` 의 `RedissonAutoConfiguration`
(Spring Boot 4용 `RedissonAutoConfigurationV4`)를 sources JAR 에서 직접 확인:
```java
@AutoConfigureBefore(RedisAutoConfiguration.class)          // Lettuce 자동설정보다 먼저
@ConditionalOnMissingBean(RedisConnectionFactory.class)
public RedissonConnectionFactory redissonConnectionFactory(RedissonClient redisson) { ... }

@ConditionalOnMissingBean(StringRedisTemplate.class)
public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory f) { ... } // 위 Redisson factory 주입
```
→ Redisson 이 Lettuce 자동설정보다 **먼저** `RedissonConnectionFactory` 를 등록하므로, 나중에 도는
Spring `RedisAutoConfiguration` 의 Lettuce factory 는 `@ConditionalOnMissingBean` 에 걸려 **생성 자체가
안 된다**. `StringRedisTemplate` 은 그 Redisson factory 를 주입받는다. **Lettuce 미사용 확정.**
보조 관측(정합): 스타트업 로그 `Redisson 4.4.0 … 24 connections initialized` + `lettuce_command_*`
런타임 0건. → 코드 주석("Lettuce 계측 활성화")과 실제 동작이 불일치(죽은 계측).

> **검증 경위(2026-07-01)**: beans 엔드포인트 노출(compose env 주입 실패)·Redis CLIENT LIST(재기동으로
> seed 유실) 시도는 이 환경에서 부적합했고, **starter 소스 직독**이 결정적 확정 방법이었다.

### 방침 = B / 방안 1: 데이터 경로 전체를 Lettuce 로 (2026-07-01 결정)
공유 `StringRedisTemplate` 하나가 **조회(SCARD)·선점(SREM/SPOP)·입장(INCR)·토큰(SET/GET/DEL)** 을
전부 처리하므로(§2.6.0), 그 template 의 factory 를 Lettuce 로 고정하면 **데이터 경로 전체**가 Lettuce 로
간다. **분산 락은 `RedissonClient` 를 직접 쓰므로 factory 와 무관하게 그대로 유지.**
- 효과: `lettuce_command_*` 로 SCARD 뿐 아니라 SREM/SPOP·INCR 까지 **명령별 지연·왕복** 고해상도 계측.
  부가 이득 — 나중에 E1(락)·L1(단일좌석 경쟁) 분석에도 Redis 지연 데이터 확보.
- 설계 정합: 프로젝트 원래 의도(**데이터=Spring Data/Lettuce, 락=Redisson**)로 되돌리는 것.
  `RedisConfig` 의 `ClientResources`(현재 죽은 코드)를 의도대로 살린다.
- 방안 2(조회 전용 template 만 분리)는 한 컴포넌트가 두 factory 혼용 + 선점 미계측이라 기각.
  대안 A(Redisson 자체 계측)는 명령별 해상도 낮아 기각. C(간접)는 ②③ 판정 약해 기각.

### 산출물
- `lettuce_command_*`(예: `lettuce_command_completion_seconds`) Prometheus 수집.
- Grafana 에 **Hikari·Redis 패널 대시보드 추가**(`monitoring/grafana/dashboards/`) — pending·acquire·
  usage(Hikari) + command latency·count(Redis)를 한 화면에서 Before/After 로 캡처.
- 정합성: 데이터 경로가 Lettuce 로 바뀌어도 SREM/SPOP/SCARD/SADD/INCR 동작·결과가 Redisson 경로와
  동일해야 함. 기존 통합 테스트(선점·reconcile·입장) + `ConcurrencyPocTest`(oversell 0) 통과가 게이트.

---

## 2.6 B 구현 상세 (방안 1)

### 2.6.0 공유 template 사용처 (변경 영향 범위)
| 컴포넌트 | 연산 | 비고 |
|---------|------|------|
| `RedisSetPreemption` | SCARD·SREM/SPOP+HSET(Lua)·SADD·HGET/HGETALL·DEL | 조회+선점+reconcile |
| `AdmissionService` | INCR·DECR·DECRBY | 입장 슬롯 |
| `EntryTokenStore` | SET(ttl)·GET·DEL | 토큰 |

세 컴포넌트 모두 `StringRedisTemplate redis` 를 **주입만** 한다 → **컴포넌트 코드는 무변경**,
주입되는 template 의 factory 만 Lettuce 로 바뀐다.

### 2.6.1 충돌 회피 원리 (핵심)
Redisson 자동설정(§2.5 근거)은 `@ConditionalOnMissingBean(RedisConnectionFactory.class)` 로
`RedissonConnectionFactory` 를 등록한다. **우리가 `LettuceConnectionFactory` 를 명시 `@Bean` 등록하면**
그 조건이 깨져(이미 있음) Redisson 은 자기 factory 를 **안 만든다** → `RedisConnectionFactory` 타입 빈이
1개(Lettuce)뿐이라 충돌·모호성 없음. 락에 쓰는 `RedissonClient` 는 **별도 조건**(`@ConditionalOnMissingBean(RedissonClient.class)`)이라 그대로 생성 → 락 정상.
(참고: redisson/redisson#5453 — 두 factory 공존 시 기동 실패. 우리는 Lettuce factory 만 남겨 회피.)

### 2.6.2 코드 변경 (`infra/RedisConfig.java`)
기존 `ClientResources` 빈은 유지하고, 아래 두 빈을 추가:
```java
@Bean
public LettuceConnectionFactory redisConnectionFactory(
        ClientResources clientResources,
        RedisProperties props) {                       // spring.data.redis.host/port 재사용
    var standalone = new RedisStandaloneConfiguration(props.getHost(), props.getPort());
    var clientConfig = LettuceClientConfiguration.builder()
            .clientResources(clientResources)          // ← Micrometer recorder 연결(계측 활성)
            .build();
    return new LettuceConnectionFactory(standalone, clientConfig);
    // @Bean 이라 afterPropertiesSet/destroy lifecycle 자동.
}

@Bean
@Primary                                                // Redisson 의 stringRedisTemplate 자동등록보다 우선
public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
    return new StringRedisTemplate(factory);
}
```
- host/port 는 `RedisProperties`(Spring Boot 가 `spring.data.redis.*` 바인딩) 주입으로 재사용 →
  env(`REDIS_HOST/REDIS_PORT`) 그대로 동작. 하드코딩 금지.
- `@Primary` 는 Redisson 의 `@ConditionalOnMissingBean(StringRedisTemplate)` 자동등록이 혹시 살아있어도
  우리 것이 우선되게 하는 안전장치.
- **Redisson 클라이언트 설정은 손대지 않는다.** 별도 `RedissonClient` 자동설정이 `spring.data.redis.*`
  로 접속(락 전용)하도록 그대로 둠.

### 2.6.3 정합성·회귀 검증 (필수 게이트)
1. **기동 검증**: app 이 뜨고 `RedisConnectionFactory` 주입 모호성/기동 실패가 없어야 함
   (Lettuce factory 1개만 존재). Redisson 락 초기화 로그 정상.
2. **계측 검증**: 부하/조회 후 `curl /actuator/prometheus | grep lettuce_command` 로 미터 생성 확인.
3. **선점 정합성**: `ConcurrencyPocTest`(동시 1,000 oversell 0)·선점/reconcile 통합·입장 제어·토큰
   테스트 **전량 통과**. Lua 스크립트(SREM/SPOP+HSET)가 Lettuce 에서도 동일 동작하는지가 핵심.
4. **통합 회귀**: `./gradlew test` 전체 그린.

### 2.6.4 Grafana 대시보드 추가
- `monitoring/grafana/dashboards/` 에 Redis·Hikari 패널 JSON 추가(`dashboard.yml` provider 가 폴더
  자동 로드하므로 파일만 두면 됨).
- 패널: Hikari(active/pending/acquire p99/usage) + Redis(`lettuce_command_completion_seconds` p95/p99·
  rate, first-response latency). Before/After 캡처용 시간범위 변수.

### 2.6.5 착수 전 최종 확인
- 코드 변경 전, 현 상태가 정말 Redisson factory 인지 런타임 재확인은 **소스 근거(§2.5)로 이미 확정**됐으므로
  생략 가능. 다만 변경 후 §2.6.3-1(기동·계측) 으로 결과를 반드시 실측 확인한다.

---

## 3. 최적화 기법 (4종) — 각각 토글 on/off

목적: 하나만 고르는 게 아니라 **각 기법의 효과를 개별 측정**한다. 그래서 4종을 서로 독립적으로
켜고 끌 수 있게 구현한다(프로퍼티 토글). 켜는 조합에 따라 독립·누적 측정을 같은 코드로 커버.

| # | 기법 | 노림(어느 지표를) | 토글(안) | SCARD 왕복 | 점유(usage) | 일관성 |
|---|------|------|------|:---:|:---:|:---:|
| ① | **DB pool size 상향** | acquire·pending(대기 큐) | `spring.datasource.hikari.maximum-pool-size` | 그대로 N | — | 불변 |
| ② | **Redis 를 tx 밖으로** | usage(커넥션이 Redis 왕복 안 감쌈) | `booking.query.redis-outside-tx` | 그대로 N | ↓↓ | 불변 |
| ③ | **Redis pipeline** | RTT(N회 → 1회) | `booking.query.pipeline` | N→1 | ↓ | 불변 |
| ④ | **Redis 공유 단기 캐시**(TTL ≤ 2s) + single-flight | SCARD·DB 조회 자체 제거 | `booking.query-cache.enabled` | 0 SCARD(+GET 1) | ↓↓↓ | 약(≤2s) |

### 각 기법 설계 메모

- **① pool size** — 코드 무변경, 설정만. "크다고 좋은 게 아님"(HikariCP): DB 병렬 능력을 넘으면
  컨텍스트 스위칭·락 경합만 늘고 **예매 경로가 같은 풀 공유**라 조회가 독식하면 핵심 SLO 가 굶는다.
  상한 공식 `((core×2)+spindle)` 을 기준으로 **여러 값(예: 10/20/30/50)을 측정**해 포화 해소 지점과
  역효과 지점을 함께 본다. MySQL 컨테이너 코어 수 확인 후 후보 값 확정.
- **② Redis 를 tx 밖으로** — 페이지 조회(DB, `@Transactional`)와 잔여석 집계(Redis)를 **분리**해,
  DB 커넥션이 Redis 왕복을 기다리며 점유되지 않게. 트랜잭션 경계를 DB 쿼리로 좁히고 SCARD 는
  트랜잭션 종료(커넥션 반환) 후 수행. 잔여석 값·매진 판정 로직은 불변.
- **③ Redis pipeline** — N회 SCARD 를 **1회 왕복**으로 묶음(`RedisTemplate.executePipelined`
  또는 Lettuce 파이프라인). 반복 자체는 남지만 왕복 지연이 RTT×N → RTT×1. ②와 **독립**(tx 안이든
  밖이든 파이프라인화 가능)이라 조합 측정 대상.
- **④ Redis 공유 단기 캐시** — 리스트+잔여석을 짧은 TTL(≤2s)로 캐시 → SCARD·DB 조회를 아예 제거.
  **2-tier 일관성 모델이 명시 허용**(조회/표시는 약한 일관성, staleness ≤ ~2s). **이 토글이 곧 E3 의
  cache on**. 매진은 보수적(캐시로 "매진인데 available" 표시 금지, 반대 방향만 허용). 상세 = §3.5.

### 3.5 ④ 상세 설계 — Redis 공유 캐시 + single-flight (2026-07-06 개정)

> **왜 로컬(Caffeine) 아닌 Redis 공유인가**: 이 프로젝트 목적은 실 KTX 예매(멀티 인스턴스 전제) 구현이다.
> 잠긴 2-tier 결정도 표시 경로를 "**served from Redis counters / short-TTL cache**"로 명시한다(CLAUDE.md).
> 로컬 캐시는 인스턴스별로 값이 갈려 표시가 인스턴스 간 불일치 → 공유 캐시가 정합. 히트가 GET 1왕복으로
> 0 은 아니지만, T4-5 가 규명한 병목은 **DB 풀 점유**이고 GET 은 tx 밖·풀 무접촉이라 병목은 그대로 제거된다.

- **키/값**: 키 `qcache:list:{dep|arr|from|cursorId|pageSize}`, 값 = `ScheduleListResponse` **JSON**.
  직렬화는 주입 `ObjectMapper`(Boot 기본 JavaTimeModule → `LocalDateTime` ISO). `StringRedisTemplate`
  (@Primary·Lettuce 계측 경로)로 `opsForValue().set(key, json, ttl)` → 캐시 GET/SET 이 `lettuce_command_*`
  로 잡혀 E3 지표(SCARD rate 붕괴 → GET rate)로 관측된다. TTL **기본 1s**(SLA 2s 아래 여유).
- **히트 경로**: GET 1회, DB 커넥션 미획득 + SCARD×N 소거. 미스 경로는 ②③ 조합(`computeUncached`)을
  그대로 타 **C4(전부 on) 자연 합성**.
- **single-flight(stampede 방어) — double-checked locking**: 공유 캐시 + 핫키는 TTL 만료 순간 여러
  인스턴스가 동시에 미스 → 일제히 DB 재계산(thundering herd). 이를 기존 `DistributedLock.executeWithLock`
  으로 막는다:
  1. GET 히트 → 반환.
  2. 미스 → `executeWithLock(qcacheKey, action)`. action = **캐시 재-GET**(winner 가 이미 채웠으면 그 값
     반환 → loser 는 재계산 안 함) → 여전히 미스면 `loader.get()`(②③ 컴퓨트) → SET(ttl) → 반환.
  3. 반환 null(=락 WAIT 타임아웃, 희귀) → 직접 `loader` + SET 폴백(정합성 우선). `loader` 는 non-null
     보장이라 null 은 "락 미획득"만 의미 → 폴백 신호로 안전.
  → 만료 순간 herd 가 몰려도 **실제 DB 재계산 1회**, 나머지는 락 뒤 재-GET 히트로 즉시 통과.
- **락 timing 재사용**: `RedissonDistributedLock` 은 WAIT 5s·LEASE 10s(예매용 튜닝). 캐시엔 길어 보이나
  double-check 로 loser 실 대기 ≈ winner 컴퓨트 시간(수십 ms)에 수렴, 5s 는 degenerate ceiling. 그대로
  재사용. 만료 스파이크가 측정에서 문제되면 캐시 전용 short-wait 락으로 분리(폴백안).
- **토글(②③ 동일 패턴)**: `QueryCacheProperties(enabled, ttl)`(prefix `booking.query-cache`) + yml +
  compose env `BOOKING_QUERY_CACHE_ENABLED`(기본 false=Before). 프록시 기반 `@Cacheable` 은 런타임 토글
  불가 → 수동 분기(②③과 동일 사유). `ScheduleQueryService.search()` 에서 enabled 면
  `cache.getOrLoad(key, () -> computeUncached(...))`.
- **정합성**: 표시 staleness ≤TTL(전역 1창, 인스턴스 간 일관). **오버셀 0** — 캐시는 예매 경로 미접촉,
  예매는 강한 일관성 critical section 에서 재검증. "매진인데 available" 이 ≤TTL 뜰 수 있으나 예매 시도가
  SREM=0 으로 자동 실패 → self-correct(2-tier 계약 내). README C6 트레이드오프: 로컬 대신 공유(일관성) +
  single-flight(stampede 방어).

> **측정 후 추가(2026-07-07) — TTL jitter(`ttlJitterRatio`)**: 단일 핫키(A4/C4)에선 캐시가 완벽했으나,
> **다중 키(키 50개·히트율~98%)에서 여러 키가 동시에 채워져 동시에 만료 → 만료 순간 미스가 뭉쳐 DB 파도
> → p95 스파이크**(5회 재현, 최악 525ms). single-flight 는 키별 재계산을 1회로 막지만 키가 50개면 동시
> 50 컴퓨트가 풀을 순간 점유한다. 완화책으로 TTL 을 `[1−ratio, 1+ratio]` 배 무작위 스케일(`put()`)해 만료를
> 시간축에 분산 → **최악 p95 절반(525→265ms). 다만 SLO 200ms 는 여전 초과** — jitter 는 만료 뭉침만 흩을
> 뿐 미스 총량은 불변이라, 다중 키의 근본(미스 시 DB 풀 경유)은 안 바뀐다. **결론: 캐시는 히트율 의존** —
> 핫키(실 KTX 인기 노선)엔 강하나 낮은 히트율엔 DB 풀이 병목으로 복귀. jitter 는 무해 상시 옵션(기본 0,
> env `BOOKING_QUERY_CACHE_TTL_JITTER`). 낮은 히트율 대응(②결합·pool↑·DB 인덱싱)은 운영점 밖 후속 과제.

---

## 4. 실험 설계 — 독립 + 누적 (둘 다)

측정 방식: **둘 다** (2026-07-01 결정). 각 기법을 토글로 켜고 끄며 아래 두 축을 측정.

### 4.1 독립 측정 (각 기법 단독 효과)
매번 **baseline(pool10 · SCARD 직렬 tx안 · 캐시off)** 에서 **기법 하나만** 켜서 L3 재측정.
→ "이 기법 단독으로 포화점을 얼마나 올리나"를 순수 비교.

| 실험 | ① | ② | ③ | ④ | 본다 |
|------|:-:|:-:|:-:|:-:|------|
| baseline (=§1) | off | off | off | off | 기준선 ~780 TPS |
| A1 pool만 | **on** | off | off | off | 풀 상향 단독 |
| A2 tx밖만 | off | **on** | off | off | 점유시간 단독 |
| A3 pipeline만 | off | off | **on** | off | RTT 단독 |
| A4 캐시만 | off | off | off | **on** | 캐시 단독(=E3 after) |

### 4.2 누적 측정 (사용자 지정 순서로 쌓기)
지정 순서 **① → ② → ③ → ④** 로 하나씩 더해가며 L3 재측정. → "최종 시스템이 어디까지 가나 +
단계별 증분 기여".

| 실험 | ① | ② | ③ | ④ | 본다 |
|------|:-:|:-:|:-:|:-:|------|
| C1 | **on** | off | off | off | +pool |
| C2 | on | **on** | off | off | +tx밖 |
| C3 | on | on | **on** | off | +pipeline |
| C4 | on | on | on | **on** | 전부(최종 도달점) |

### 4.3 측정 축·게이트 (매 실험 공통)
- 실효 TPS(포화점)·조회 p95(**dropped=0 인 유효 구간에서**)·usage·acquire·pending(Prometheus).
- `dropped_iterations` 가 남으면 목표 TPS 미발사 → 해당 p95 는 포화값(절대치 신뢰 불가)로 표기.
  포화 해소 후엔 포화점 아래(예 RATE 600~800)로도 돌려 **유효 구간 p95** 확보.
- 동일 도착률로 비교(L3 `RATE` 고정, 기본 3,000). E3 는 ④ on/off 를 동일 RATE 로 대조.

### 4.4 E3(T4-9) 매핑
- **Before(cache off)** = ④ off = §1 L3 실측(이미 확보).
- **After(cache on)** = ④ on = 실험 A4/C4. 스크립트는 `load-tests/experiments/E3_*.js` 준비 완료,
  ④ 토글 구현이 E3 의 남은 범위.

---

## 5. 정합성·회귀 가드 (반드시 유지)

- **①/②/③**: 잔여석 수치·매진 판정(`remainingSeats == 0 ⇒ soldOut`)이 baseline 과 동일해야 함.
  기존 `ScheduleQueryServiceTest` + 통합 테스트 통과가 게이트.
- **②(tx 밖)**: 트랜잭션 경계 축소가 다른 읽기 정합성을 깨지 않는지(단일 조회라 리스크 낮음) 확인.
- **④(캐시)**: 매진은 **보수적**이어야 한다 — staleness 로 "매진인데 available" 표시 금지(반대 방향
  "available 인데 매진"은 안전). TTL ≤ 2s 로 staleness SLO 준수.
- **①(pool 상향)**: 예매 경로가 같은 풀 공유 → 조회 최적화가 **예매 p95(≤500ms)를 회귀시키지
  않는지** L2 혼합 부하로 교차 검증.

---

## 6. 산출물

- 독립(§4.1)·누적(§4.2) 각 실험의 Before/After 수치·그래프 → `docs/results/P4_Result.md`
  (T4-5 / T4-9 E3 섹션).
- **기법별 기여도 분리**(단독 효과 + 증분 효과) → E3 rationale·README(C2·C6) 근거.
- 최종 pool 크기 결정값 + 근거(공식·측정) → `application.yml` 반영 시 주석으로 사유 명시.
- 토글 4종의 구현 위치·프로퍼티명 → 코드 주석 + 이 문서 §3 표 갱신.

---

## 7. 진행 로그

| 날짜 | 단계 | 내용 | 상태 |
|------|------|------|:----:|
| 2026-07-01 | Before 측정 | L3 3회 → ~780 TPS 포화·p95 9.5s·dropped 46만. Prometheus: 풀10/pending190/acquire3.9s/usage644ms 규명 | ✅ |
| 2026-07-01 | 전략 결정 | 4기법(pool→tx밖→pipeline→캐시) 각각 토글 측정, 독립+누적 둘 다 | ✅ |
| 2026-07-01 | 계측 진단 | Redis 지표 수집 0건 확인 → 원인=Redisson factory 대체로 Lettuce 계측(RedisConfig) 우회. Grafana=jvm.json만(Hikari·Redis 패널 없음) | ✅ |
| 2026-07-01 | 계측 방침 | B/방안1(데이터 경로 전체 Lettuce, 락은 Redisson) 확정. 원인=Redisson autoconfig 소스 직독으로 확정 | ✅ |
| 2026-07-01 | B 구현 설계 | LettuceConnectionFactory 명시등록→Redisson factory 미생성(충돌회피), StringRedisTemplate @Primary. §2.6 상세 | ✅ |
| 2026-07-01 | B 구현 | RedisConfig 에 Lettuce factory+@Primary template. host/port 는 DataRedisConnectionDetails 주입(@Value 는 @ServiceConnection 동적포트 놓쳐 테스트 오염→교정) | ✅ |
| 2026-07-01 | 게이트 검증 | 131 테스트 그린(ConcurrencyPoc oversell 0 포함). app 재빌드 기동 정상(factory 충돌 없음). lettuce_command_*(SCARD/HGETALL/SMEMBERS…) Prometheus 수집 확인 | ✅ |
| 2026-07-01 | Grafana | redis-hikari.json 대시보드 추가(Hikari active/pending/acquire/usage + Redis command rate/latency/SCARD/firstresponse). 로드·쿼리 데이터 확인 | ✅ |
| 2026-07-01 | ① pool size 토글·측정 | DB_POOL_SIZE env 외부화(러너 actuator 검증). pool 10/20/50 sweep(×2회): TPS 849→924→950→966(+14%만, 한계효용 체감)·pending 190→149·usage 0.84→0.21s. **pool 은 지렛대 아님**(~960 TPS 점근, SLO 미달 유지) → 근본=점유시간. 결과: P4_Result.md T4-5 | ✅ |
| 2026-07-04 | ② 구현 | tx DB 단위를 `ScheduleQueryReader` 로 분리(프록시 제약 회피)·서비스 토글 분기·`train` fetch join 으로 tx밖 detached 안전. 단위 12 + 통합 1(on/off 등가·lazy) green | ✅ |
| 2026-07-04 | ② 측정·독립 A2 | pool10 고정 off↔on(각3회). **usage_mean 6.92→2.58ms(−63%)·처리량 1,237→2,000/s(+62%)·acquire 133→2.3ms(−98%)·pending 190→77**. Little's law 정합, ①(+14%)의 4배 지렛대=근본 점유시간 확증. 단 여전히 SLO 미달(포화). usage 는 mean 으로 판정(max 는 outlier 지배). 결과: P4_Result.md T4-5 ② | ✅ |
| 2026-07-04 | ② 측정·누적 C2(1차) | pool50 off 2회만 유효(1,533·1,573/s·p95 3.6/3.1s) 후 **k6 원격 jslib(`k6-summary`) 컨테이너 DNS 해석 실패**(`no such host`)로 run3·txon 전량 init 실패 → txon 미확보. 코드 아님, 네트워크 flake. 재시도 예정(재발 시 jslib 로컬 vendoring). | ⚠️ 재시도 |
| 2026-07-04 | ② 측정·누적 C2(재시도) | jslib vendoring 후 pool50 off↔on(각3회) clean. **C1 pool50·tx안 1,604/s(usage 26.4ms)→C2 pool50·tx밖 1,976/s(usage 5.13ms, +23%)**. 결정타: **pool10·tx밖(2,000)≈pool50·tx밖(1,976)** → **② 위에 ①(pool) 얹어도 이득 ≈0, ①②는 상호 대체재**(② 가 커넥션 hold 소거로 pool 이 풀 문제 자체를 없앰). pool↑ 는 2코어 DB 경합으로 usage↑. 운영 pool 기본 10 유지. 결과: P4_Result.md T4-5 ② 누적 | ✅ |
| 2026-07-04 | ③ 구현 | `SeatPreemption.availableCounts` 배치(SCARD pipeline) + 조회 경로 토글(`booking.query.pipeline`, ②와 독립 2×2). `executePipelined` 는 SessionCallback 사용(RedisCallback 의 connection 은 프록시라 StringRedisConnection 캐스팅 불가). 단위 + 실 Redis 통합테스트(순서보존·직렬등가·빈입력) green | ✅ |
| 2026-07-06 | ③ 결론(측정 갈음) | **측정 안 함 — 효과크기 논증으로 갈음.** pipeline 절감=RTT×(N−1). 실운영 N≈8(`DEFAULT_LIMIT`)에선 ~7RTT≈**~1.4ms** = 회차 간 노이즈·측정 바닥 이하 → **N≈8 에선 조회 p95/포화점 레버 아님**. N 을 50(시드 상한)으로 부풀리면 ~10ms 로 겨우 가시하나 **운영점(N≈8±2)이 아니라 measurement theater** → 기각. 코드는 대용량 페이지 안전용으로 유지(무해, N↑ 시 자동 이득). 남은 격차 실질 레버=④(캐시=E3, 왕복을 줄이는 ③이 아니라 없애는 ④). 결과: P4_Result.md T4-5 ③ | ✅ |
| 2026-07-06 | ④ 계획 개정 | 캐시 기술을 **Redis 공유 + single-flight** 로 확정(§3.5). 로컬(Caffeine)은 멀티 인스턴스에서 표시 불일치 → 잠긴 2-tier("served from Redis short-TTL cache")와 정합하게 공유 선택. 히트=GET 1왕복(0 아님)이나 DB 풀 병목은 tx밖 GET 이라 그대로 제거. stampede 는 기존 `DistributedLock` double-check single-flight 로 DB 재계산 1회 보장. | 📝 계획 |
| 2026-07-07 | ④ 구현 | `QueryCacheProperties`(enabled·ttl·ttlJitterRatio) + `ScheduleListCache`(single-flight double-check). Jackson 3(`tools.jackson.databind`) — Spring Boot 4.0 기본 ObjectMapper 가 3.x 라 2.x 아닌 3 로 작성(예외=unchecked `JacksonException`). 서비스에 `computeUncached` 추출 + 캐시 분기. 단위 6(히트/loser/winner/락null/degrade/지터) + 통합(on≡off 등가·round-trip). 전체 test 그린(oversell 0 포함). | ✅ |
| 2026-07-07 | ④ 측정 A4·C4(=E3 after) | **단일 핫키(키 1개·히트율~100%).** A4(baseline 위 cache off↔on): 850→**2,497 TPS**·p95 8s→**26ms**·dropped 44만→**0** = SLO 통과(포화 해소). C4(②tx밖 위): cache on 2,498≈A4 → **캐시 켜지면 ①②③ 잉여**(A4≈C4, ②가 ①을 잉여로 만든 것의 연장). 3회 일관. 결과: P4_Result.md T4-5 ④ | ✅ |
| 2026-07-07 | ④ 한계 규명 다중 키(L3b) | `from` 50일 분산(키 50개·히트율~98%)으로 낮은 히트율 실측. **고정 TTL: 동시 만료 스파이크 재현(5회)** — p95 31~525ms 요동·max 초단위·dropped 발생(단일키는 22~32ms 타이트). single-flight 는 키별 1회 막지만 키 50개라 동시 50 컴퓨트가 풀 점유. **TTL jitter 0.2: 부분 완화**(최악 p95 525→265ms, 그러나 SLO 200ms 초과 유지) — jitter 는 만료 뭉침만 흩을 뿐 미스 총량은 불변. 결론: **캐시는 히트율 의존**(핫키 강·낮은 히트율선 DB 풀 병목 복귀). jitter 는 무해 상시 옵션(기본 0). 결과: P4_Result.md T4-5 ④ 한계 | ✅ |
