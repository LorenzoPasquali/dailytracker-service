package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "\"NotificationRecipient\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "\"ruleId\"", nullable = false, insertable = false, updatable = false)
    private Integer ruleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"ruleId\"", nullable = false)
    private NotificationRule rule;

    @Column(nullable = false, length = 255)
    private String email;
}
