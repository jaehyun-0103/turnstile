package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.dto.ConcertResponse;
import com.turnstile.backend.domain.concert.dto.SeatListResponse;
import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.concert.service.ConcertService;
import com.turnstile.backend.global.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataRedisTest
@Import({RedisConfig.class, ConcertService.class})
class ConcertServiceCacheTest {

    @Autowired private ConcertService concertService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @MockitoBean private ConcertRepository concertRepository;
    @MockitoBean private ConcertScheduleRepository concertScheduleRepository;
    @MockitoBean private SeatRepository seatRepository;

    @BeforeEach
    void setUp() {
        // 테스트 간 캐시 오염을 막기 위해 Redis 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("콘서트 목록 최초 조회 후 캐시 적용")
    void getConcertsCacheHitTest() {
        // given: 콘서트 정보 세팅
        Concert concert = Concert.builder().title("테스트 콘서트").build();
        given(concertRepository.findAll()).willReturn(List.of(concert));

        // when: 전체 콘서트 목록 조회 반복 요청
        List<ConcertResponse> result1 = concertService.getConcerts();
        List<ConcertResponse> result2 = concertService.getConcerts();

        // then: DB 조회가 최초 1회만 실행되었는지 검증
        assertThat(result1).hasSize(1);
        verify(concertRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("좌석 조회 시 모든 상태의 좌석이 반환되며 캐시 적용")
    void getSeatsCacheHitTest() {
        // given: 상태가 다른 좌석 3개 세팅
        Long scheduleId = 1L;
        Seat seat1 = Seat.builder().seatNo(1).price(150000L).status(SeatStatus.AVAILABLE).build();
        Seat seat2 = Seat.builder().seatNo(2).price(150000L).status(SeatStatus.HOLD).build();
        Seat seat3 = Seat.builder().seatNo(3).price(150000L).status(SeatStatus.SOLD).build();

        given(seatRepository.findAllByScheduleId(scheduleId)).willReturn(List.of(seat1, seat2, seat3));

        // when: 동일한 회차 좌석 반복 조회
        SeatListResponse result1 = concertService.getSeats(scheduleId);
        SeatListResponse result2 = concertService.getSeats(scheduleId);

        // then: 상태 필터링 없이 3개 모두 반환 및 DB 조회 1회 수행 검증
        assertThat(result1.seats()).hasSize(3);
        assertThat(result1.seats().get(1).status()).isEqualTo("HOLD");
        assertThat(result2.seats()).hasSize(3);

        verify(seatRepository, times(1)).findAllByScheduleId(scheduleId);
    }
}