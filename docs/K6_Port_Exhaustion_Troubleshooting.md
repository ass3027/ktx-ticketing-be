> 상위 문서: `KTX_Ticketing_Performance_Test.md`
> 출처: 2026-06-21 세션 — T4-3 L1 측정 중 발생한 connection refused/포트 압박 규명 및 우회

# k6 부하 테스트 포트 고갈 트러블슈팅 — Windows Docker Desktop NAT 우회

## 0. 한 줄 요약

1,000 VU L1 측정에서 다수 요청이 `connection refused`로 떨어진 진짜 원인은
앱·커널 튜닝 문제가 아니라 **Windows Docker Desktop의 포트 프록시(user-space NAT)**
구간에서의 **ephemeral 포트/TIME_WAIT 압박**이었다.
해결은 커널 sysctl 튜닝이 아니라 **k6를 app과 같은 docker 네트워크에 두고 `app:8080`으로
직결**해 NAT 구간 자체를 제거한 것이다.

## 1. 증상

- L1(단일 좌석 1,000 VU 경쟁)을 **컨테이너 k6 → host 포트 매핑(`127.0.0.1:8080`)** 으로 돌릴 때
  요청 다수가 `connection refused`(RST). T4-3 측정에서 **refused 631건** 관측.
- 동일 스크립트를 **호스트 JVM 직접 실행**으로 바꾸면 refused ≈ 0.
- 부하를 반복하면 호스트 쪽 소켓이 `TIME_WAIT`으로 누적되며 재현성이 악화.

## 2. TCP 배경 — TIME_WAIT은 "누가, 어느 방향"이 핵심

- **TIME_WAIT은 TCP를 먼저 닫는 쪽(active close)에 쌓인다.** 부하 테스트에서 연결을
  대량으로 열고 닫는 쪽은 **클라이언트(연결 개시자)** 이므로, ephemeral 포트 고갈/TIME_WAIT
  압박은 본질적으로 **클라이언트 측 문제**다.
- **서버(app)는 이 문제의 당사자가 아니다.** listen 포트는 `8080` 고정이고 4-tuple은
  클라이언트 포트로만 달라지므로 서버는 ephemeral 포트 고갈이 발생하지 않는다. 서버 쪽
  TIME_WAIT은 포트 고갈이 아니라 메모리/`tcp_max_tw_buckets` 압박일 뿐이다.

## 3. 왜 "컨테이너 k6 → host 포트 매핑"에서 터졌나

Windows Docker Desktop은 `ports: ["8080:8080"]` 매핑을 리눅스 커널 NAT가 아니라
**user-space 포트 프록시**(`com.docker.backend` / vpnkit 계열)로 중계한다. 그 결과
요청 한 건이 **두 번의 연결**을 만든다.

```
k6(컨테이너) ──(1)──▶ host 127.0.0.1:8080 ──[포트 프록시]──(2)──▶ app(컨테이너):8080
```

- (1)·(2) 양쪽 모두에서 ephemeral 포트를 소비하고, 프록시가 close를 주도하면 그 자리에
  TIME_WAIT이 쌓인다 → **이중 압박**.
- 1,000 VU가 동시에 몰리면 프록시 단이 포화되어 새 연결이 **RST(`connection refused`)** 로
  거절된다. 이게 refused 631의 정체다.

## 4. 막다른 길 — `net.ipv4.tcp_tw_reuse=1`을 app 컨테이너에 거는 안

검토했으나 **이 문제에 맞는 레버가 아니다.** 적용 대상도, 방향도, 환경도 어긋난다.

1. **`tcp_tw_reuse`는 outbound(연결을 거는 쪽) 전용.** TIME_WAIT 소켓을 *새 outbound 연결*에
   재사용하도록 허용하는 옵션이라 **클라이언트 측 레버**다.
2. **app은 서버 → 무효.** 서버 listen 포트(8080)는 ephemeral 고갈이 없고, 서버 쪽 TIME_WAIT에는
   `tcp_tw_reuse`가 **작동하지 않는다**. (서버측 TIME_WAIT을 건드리던 `tcp_tw_recycle`은
   커널 4.12에서 제거 + NAT 뒤에서 연결이 깨지는 위험으로 사용 금지.)
   app에 걸어 영향받는 건 오직 `app→MySQL`·`app→Redis` 같은 app이 클라이언트로 거는 연결뿐인데,
   이건 **HikariCP/Lettuce 커넥션 풀**로 상시 유지되어 TIME_WAIT이 쌓일 일이 없다.
3. **Windows라 적용 위치도 어긋남.** `sudo sysctl -w`를 Windows 호스트에서 실행할 수 없고
   (커널은 WSL2/VM 안), 컨테이너에 넣으려면 compose `sysctls:`를 써야 하지만 위 1·2 때문에
   app에 넣어도 효과가 없다. 굳이 쓴다면 대상은 **app이 아니라 k6(클라이언트) 컨테이너**다.

## 5. 채택한 해결 — same-network 직결로 NAT 제거 (`docker-compose.k6.yml`)

k6를 app과 **동일한 compose 기본 네트워크**에 띄우고, host 포트 매핑이 아니라 DNS 이름
`app:8080`으로 **컨테이너↔컨테이너 직결**한다. 포트 프록시 구간이 통째로 사라진다.

```
k6(컨테이너) ──────────▶ app(컨테이너):8080      # 포트 프록시 미경유
```

- `BASE_URL: http://app:8080` 주입, host ephemeral/TIME_WAIT·NAT 압박을 **근원에서 제거**.
- `profiles: ["loadtest"]`로 분리 → 평소 `docker compose up`엔 안 뜬다.
- `depends_on: app: service_healthy`로 app 준비까지 자동 대기.

> 이것은 증상 회피가 아니라 **병목(포트 프록시)을 제거한 근본 수정**이다. 커널 튜닝으로
> 증상을 누르는 것보다 깨끗하다.

## 6. 더 줄이고 싶을 때의 진짜 레버

| 레버 | 위치 | 효과 |
|------|------|------|
| **연결 재사용(keep-alive)** | k6 (기본 ON) | 연결 수 자체를 줄여 TIME_WAIT을 안 쌓이게 함 — 가장 큰 레버 |
| `tcp_tw_reuse=1` + `ip_local_port_range` 확대 | **k6(클라이언트) 컨테이너** | 의도적 연결당-1요청 부하에서 client 포트 부족 완화 |
| same-network 직결 | 이미 적용 | NAT 제거 = 근본 |

## 7. 교훈

- **TIME_WAIT/포트 고갈은 "어느 호스트, 어느 방향"을 먼저 특정해야 한다.** 방향을 틀리면
  맞는 곳(클라이언트)이 아니라 엉뚱한 곳(서버)을 튜닝하게 된다.
- **측정 환경의 인공물(Windows 포트 프록시)이 SUT 성능으로 오인되지 않게** 부하 생성기를
  SUT와 같은 네트워크 평면에 두는 것이 측정 신뢰성의 전제다.
- 커널 플래그를 켜기 전에 **그 플래그가 정확히 어느 소켓에 작용하는지**(outbound vs inbound,
  active-closer vs passive)를 확인한다.

---

## 관련 파일

- `docker-compose.k6.yml` — same-network 직결 구성
- `load-tests/common/config.js` — `BASE_URL` 주입(127.0.0.1 IPv4 고정 이유 포함)
- `docs/P4_Result.md` — T4-3 refused 631 → 호스트 실행 우회 측정 기록
