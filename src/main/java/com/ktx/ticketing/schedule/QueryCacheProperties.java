package com.ktx.ticketing.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 조회 단기 캐시 토글(T4-5 ④ = 실험 E3). 운행편 리스트 조회를 Redis 공유 캐시로 감싸 히트 시 DB 커넥션
 * 미획득 + SCARD×N 소거 — T4-5 가 규명한 DB 풀 병목을 근본에서 우회한다({@code Query_Path_Optimization_Plan.md} §3.5).
 *
 * @param enabled ④: 조회 캐시를 켤지 여부. {@code true}=E3 after(캐시 히트로 DB/SCARD 소거),
 *                {@code false}(기본/Before)=매 조회마다 DB+SCARD 직접 집계. {@code @Cacheable} 프록시는 런타임
 *                토글이 안 돼(②③과 동일 사유) 서비스에서 수동 분기한다.
 * @param ttl     캐시 staleness 상한. 2-tier 일관성 계약(표시 경로 staleness ≤ ~2s)을 지키도록 기본 1s.
 *                예매 경로는 캐시를 안 타므로 오버셀과 무관 — "매진인데 available" 이 ≤ttl 뜰 수 있으나
 *                예매 시도가 SREM=0 으로 self-correct 한다.
 */
@ConfigurationProperties(prefix = "booking.query-cache")
public record QueryCacheProperties(boolean enabled, Duration ttl) {
}
