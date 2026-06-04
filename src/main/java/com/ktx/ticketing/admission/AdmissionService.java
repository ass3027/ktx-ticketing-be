package com.ktx.ticketing.admission;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 입장 제어 — 활성 세션 수를 상한 K 미만으로 유지하며 EntryToken 을 발급한다.
 *
 * <p>동시성: {@code active:{scheduleId}} 를 <b>INCR 한 반환값으로 판정</b>한다(검사-후-증가의 race 회피).
 * INCR 결과가 K 를 넘으면 곧바로 DECR 로 롤백하고 거절한다. 입장 제어는 근사 일관성으로 충분하므로
 * Lua 원자화 없이 INCR/DECR 만으로 상한을 지킨다.
 */
@Service
@RequiredArgsConstructor
public class AdmissionService {

    static final String KEY_PREFIX = "active:";

    private final StringRedisTemplate redis;
    private final EntryTokenStore tokenStore;
    private final AdmissionProperties properties;

    public AdmissionResult tryEnter(Long scheduleId, Long userId) {
        Long active = redis.opsForValue().increment(activeKey(scheduleId));
        if (active != null && active > properties.maxActive()) {
            redis.opsForValue().decrement(activeKey(scheduleId)); // 슬롯 점유 실패 → 즉시 롤백
            return new AdmissionResult.Rejected(properties.retryAfter());
        }
        EntryToken token = tokenStore.issue(scheduleId, userId, properties.tokenTtl());
        return new AdmissionResult.Admitted(token);
    }

    /** 세션 종료(예매 완료/취소/만료) 시 활성 슬롯을 반환한다. T3-8/T3-9 에서 호출. */
    public void leave(Long scheduleId) {
        redis.opsForValue().decrement(activeKey(scheduleId));
    }

    private String activeKey(Long scheduleId) {
        return KEY_PREFIX + scheduleId;
    }
}
