# P0 셋업 결과

> 작성일: 2026-06-01
> 담당: T0-1 ~ T0-4 완료

---

## 생성된 파일

| 파일 | 내용 |
|------|------|
| `build.gradle.kts` | Spring Boot 3.3 / Java 17 / Redisson / MySQL / Redis |
| `settings.gradle.kts` | 프로젝트명 설정 |
| `gradle/wrapper/` | Gradle 8.8 wrapper (JAR 포함) |
| `gradlew` / `gradlew.bat` | 빌드 스크립트 |
| `src/main/java/com/ktx/ticketing/KtxTicketingApplication.java` | Spring Boot 진입점 |
| `src/main/resources/application.yml` | DB / Redis 환경변수 기반 설정 |
| `src/main/resources/application-local.yml` | 로컬 개발용 프로파일 (`ddl-auto: update`) |
| `Dockerfile` | gradle:8.8-jdk17 빌드 → eclipse-temurin:17-jre-alpine 런타임 |
| `docker-compose.yml` | app + MySQL 8 + Redis 7 (healthcheck 포함) |
| `.github/workflows/ci.yml` | push/PR 시 빌드+테스트 자동화 |
| `.gitignore` | Gradle / IDE / Spring Boot 항목 추가 |

---

## 확정된 스택 (T0-2)

| 항목 | 결정 |
|------|------|
| 앱 프레임워크 | Spring Boot 3.3 (Java 17) |
| 빌드툴 | Gradle 8.8 (Kotlin DSL) |
| DB | MySQL 8.0 |
| Cache / 선점 | Redis 7 |
| 분산락 | Redisson 3.32 |
| 로드테스트 | k6 (우선), nGrinder (대안) |
| CI | GitHub Actions |

---

## 브랜치 전략 (T0-1)

| 브랜치 | 용도 |
|--------|------|
| `main` | 항상 배포 가능한 상태. PR + CI 통과 필수 |
| `develop` | 기능 통합 브랜치. feature/* → develop → main |
| `feature/{task-id}-{desc}` | 기능 개발 (예: `feature/t3-6-booking-seat`) |
| `test/{experiment}` | 실험/PoC 비교 (예: `test/e1-lock-comparison`) |

---

## DoD 검증 결과

### `./gradlew build` 결과
```
BUILD SUCCESSFUL in 1m 26s
7 actionable tasks: 7 executed
```

### `docker compose up --build` 후 포트 확인 (nc)
```
nc -zv -w2 127.0.0.1 6379 → Connection succeeded  (Redis)
nc -zv -w2 127.0.0.1 3306 → Connection succeeded  (MySQL)
nc -zv -w2 127.0.0.1 8080 → Connection succeeded  (App)
```

### 앱 기동 로그
```
Started KtxTicketingApplication in 4.658 seconds
Redisson: 25 connections initialized for redis/172.20.0.3:6379
Tomcat started on port 8080
```

---

## 특이사항

- Docker는 WSL 내부에서 실행 중. Windows 호스트에서 직접 `./gradlew bootRun` 시 WSL Docker 컨테이너(Redis)에 접근 불가.
- **해결**: `docker compose up --build` 로 앱도 컨테이너화하면 동일 Docker 네트워크에서 통신하므로 문제 없음.
- 로컬 IDE 개발 시에는 WSL 내부에서 실행하거나, Docker Desktop 사용 권장.
