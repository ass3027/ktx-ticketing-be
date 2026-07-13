# load-tests 작업 지침

## 측정 환경 (하드 요구)
- **WSL VM ≥ 12GB / 4vCPU 필수.** k6+앱+MySQL+Redis+모니터링이 한 WSL VM 에 공존한다.
  4GB(구 기본값)면 4k TPS 단계에서 k6 VU 급증 → VM OOM(스왑 소진) → dockerd 다운 → 측정 무효.
  `~/.wslconfig` 확인, 변경 시 `wsl --shutdown` 후 `wsl -d docker-desktop -e free -m` 로 반영 검증.
- **측정 중 `docker logs -f` 상시 스트리밍 금지.** 4k TPS 에선 앱 로그가 초당 수천 줄이라
  스트리밍 자체가 I/O·메모리 부담이 된다. 컨테이너 관측은 Prometheus/Grafana 로.

## 모니터링 스택
- **부하 측정 전 monitoring 스택을 먼저 띄운다(항상 A 방식).**
  `docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d prometheus grafana`
  러너(Run-Scenario-Container.ps1)는 `-f base -f k6` 만 쓰므로 모니터링을 자동으로 안 띄운다.
  같은 기본 네트워크라 Prometheus 가 app:8080/actuator/prometheus 를 스크레이프한다. Grafana=localhost:3000.

## 러너
- L1~L6 는 `Run-Scenario-Container.ps1`(컨테이너 k6, NAT 우회)로 실행한다. 호스트 k6 는 Windows
  ephemeral 포트/TIME_WAIT 고갈로 refused 가 섞여 측정이 오염된다.
- 코드 변경 후 첫 회차는 `-Build` 로 이미지 재빌드(안 하면 옛 코드로 도는 측정 무효).
