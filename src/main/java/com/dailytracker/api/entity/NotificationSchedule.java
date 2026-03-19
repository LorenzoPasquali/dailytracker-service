package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "\"NotificationSchedule\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "\"taskId\"", nullable = false, insertable = false, updatable = false)
    private Integer taskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"taskId\"", nullable = false)
    private Task task;

    @Column(name = "\"ruleId\"", nullable = false, insertable = false, updatable = false)
    private Integer ruleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"ruleId\"", nullable = false)
    private NotificationRule rule;

    @Column(name = "\"recipientEmail\"", nullable = false, length = 255)
    private String recipientEmail;

    @Column(name = "\"offsetMinutes\"", nullable = false)
    private Integer offsetMinutes;

    @Column(name = "\"scheduledAt\"", nullable = false)
    private Instant scheduledAt;

    @Column(name = "\"status\"", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "\"sentAt\"")
    private Instant sentAt;

    @Column(name = "\"retryCount\"", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "\"nextRetryAt\"")
    private Instant nextRetryAt;

    @Column(name = "\"errorMessage\"", length = 500)
    private String errorMessage;

    @Column(name = "\"createdAt\"", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
