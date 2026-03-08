package com.turnstile.backend.domain.reservation.entity;

import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, length = 50)
    private String groupId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private Long price;

    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;
}