package com.ktx.ticketing.admission;

/**
 * 입장 토큰. 불투명 식별자({@code value})로 Redis({@code entry:{value}})에 매핑된 활성 세션을 가리킨다.
 * 어느 운행편·사용자의 세션인지는 토큰에 직접 싣지 않고 저장소가 보관한다({@link EntryTokenStore}).
 */
public record EntryToken(String value) {
}
