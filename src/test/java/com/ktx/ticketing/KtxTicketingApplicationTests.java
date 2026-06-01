package com.ktx.ticketing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class KtxTicketingApplicationTests {

    @Test
    void applicationClassLoads() {
        // P0 스모크 테스트: 클래스 로딩 확인
        // 통합 테스트(DB/Redis 연결)는 P3에서 추가
        assertNotNull(KtxTicketingApplication.class.getName());
    }
}
