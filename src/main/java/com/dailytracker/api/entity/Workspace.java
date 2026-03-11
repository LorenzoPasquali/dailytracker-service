package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "\"Workspace\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "\"creatorId\"", nullable = false, insertable = false, updatable = false)
    private Integer creatorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"creatorId\"", nullable = false)
    private User creator;

    @Column(name = "\"isPersonal\"", nullable = false)
    private Boolean isPersonal = false;

    @Column(name = "\"createdAt\"", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
