package com.turnstile.backend.domain.concert.entity;

import com.turnstile.backend.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "seat",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"schedule_id", "seat_no"})
        },
        indexes = {
                @Index(
                        name = "idx_seat_status_hold_expires",
                        columnList = "status, hold_expires_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Seat extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ConcertSchedule schedule;

    @Column(name = "seat_no", nullable = false)
    private Integer seatNo;

    @Column(nullable = false)
    private Long price;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SeatStatus status;

    @Column(name = "hold_user_id")
    private Long holdUserId;

    @Column(name = "hold_expires_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime holdExpiresAt;
}