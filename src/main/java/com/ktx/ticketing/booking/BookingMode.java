package com.ktx.ticketing.booking;

/** 예매 모드. 두 모드는 같은 좌석 재고(avail Set)를 공유한다. */
public enum BookingMode {
    /** 직접 선택 — seatInventoryId 지정. SREM 선점(경합 집중형). */
    SEAT,
    /** 자동 배정 — 임의 가용 좌석 1개. SPOP 선점(경합 분산형). */
    AUTO
}
