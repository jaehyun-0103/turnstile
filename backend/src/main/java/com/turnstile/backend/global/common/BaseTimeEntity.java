package com.turnstile.backend.global.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 모든 엔티티의 생성 및 수정 시각을 자동으로 관리하는 기본 클래스입니다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class) // 생성일 및 수정일 자동 감지
public class BaseTimeEntity {

    @CreatedDate
    @Column(name="created_at", updatable = false, nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name="updated_at", nullable=false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;
}