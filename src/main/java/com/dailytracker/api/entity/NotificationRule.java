package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "\"NotificationRule\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "\"workspaceId\"", nullable = false, insertable = false, updatable = false)
    private Integer workspaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"workspaceId\"", nullable = false)
    private Workspace workspace;

    @Column(name = "\"projectId\"", insertable = false, updatable = false)
    private Integer projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"projectId\"")
    private Project project;

    @Column(name = "\"createdById\"", nullable = false, insertable = false, updatable = false)
    private Integer createdById;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"createdById\"", nullable = false)
    private User createdBy;

    @Column(name = "\"name\"", nullable = false, length = 120)
    private String name;

    @Column(name = "\"isActive\"", nullable = false)
    private Boolean isActive = true;

    @Column(name = "\"createdAt\"", nullable = false)
    private Instant createdAt;

    @Column(name = "\"updatedAt\"", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<NotificationRecipient> recipients = new ArrayList<>();

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<NotificationOffset> offsets = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
