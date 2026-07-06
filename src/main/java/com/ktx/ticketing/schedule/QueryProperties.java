package com.ktx.ticketing.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 조회 경로(운행편 리스트) 최적화 토글(T4-5). 각 기법을 독립적으로 켜고 꺼 효과를 개별 측정한다
 * (독립 A / 누적 C, {@code docs/plans/Query_Path_Optimization_Plan.md}).
 *
 * @param redisOutsideTx ②: 잔여석 집계(SCARD)를 DB 트랜잭션 <b>밖</b>에서 수행할지 여부.
 *                       {@code true}=DB 커넥션이 Redis 왕복을 감싸지 않아 점유시간(Hikari usage) 단축,
 *                       {@code false}(기본/Before)=SCARD 를 tx 안에서 직렬 수행. 잔여석·매진 판정은 양쪽 동일.
 * @param pipeline       ③: 페이지 편수(N)만큼의 SCARD 를 파이프라인 <b>1회 왕복</b>으로 묶을지 여부.
 *                       {@code true}=RTT×N → RTT×1({@code availableCounts}), {@code false}(기본)=직렬 N회.
 *                       ②와 독립 — tx 안/밖 어느 경로든 조합 가능(4조합). 잔여석·매진 판정은 불변.
 */
@ConfigurationProperties(prefix = "booking.query")
public record QueryProperties(boolean redisOutsideTx, boolean pipeline) {
}
