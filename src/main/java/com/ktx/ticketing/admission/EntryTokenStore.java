package com.ktx.ticketing.admission;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * EntryToken 의 Redis 저장소. {@code entry:{token}} → {@code "{scheduleId}:{userId}"}, TTL 부여.
 * 불투명 UUID 토큰을 쓰므로 검증은 Redis 조회로 이뤄지고, 만료/회수는 TTL·삭제로 일관되게 처리된다.
 */
@Component
@RequiredArgsConstructor
public class EntryTokenStore {

    static final String KEY_PREFIX = "entry:";

    private final StringRedisTemplate redis;

    /** 새 토큰을 발급하고 세션 정보를 TTL 과 함께 저장한다. */
    EntryToken issue(Long scheduleId, Long userId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(key(token), scheduleId + ":" + userId, ttl);
        return new EntryToken(token);
    }

    /** 토큰을 검증해 가리키는 세션을 돌려준다. 없거나 만료됐으면 {@code null}. 예매(T3-6)가 토큰 게이트로 사용. */
    public @Nullable EntrySession resolve(String token) {
        String value = redis.opsForValue().get(key(token));
        if (value == null) {
            return null;
        }
        int sep = value.indexOf(':');
        Long scheduleId = Long.valueOf(value.substring(0, sep));
        Long userId = Long.valueOf(value.substring(sep + 1));
        return new EntrySession(scheduleId, userId);
    }

    /** 세션 종료(예매 완료/취소) 시 토큰을 제거한다. {@code true} = 실제로 지워짐. 확정/취소(T3-8)가 호출. */
    public boolean revoke(String token) {
        return Boolean.TRUE.equals(redis.delete(key(token)));
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
