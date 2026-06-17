package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "\"Task\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String title;

    private String description;

    private String status;

    @Column(nullable = false)
    private String priority = "MEDIUM";

    @Column(name = "position")
    private Integer position;

    @Column(name = "\"createdAt\"", nullable = false)
    private Instant createdAt;

    @Column(name = "\"updatedAt\"", nullable = false)
    private Instant updatedAt;

    @Column(name = "\"userId\"", nullable = false, insertable = false, updatable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"userId\"", nullable = false)
    private User user;

    @Column(name = "\"projectId\"", insertable = false, updatable = false)
    private Integer projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"projectId\"")
    private Project project;

    @Column(name = "\"taskTypeId\"", insertable = false, updatable = false)
    private Integer taskTypeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"taskTypeId\"")
    private TaskType taskType;

    @Column(name = "\"stageId\"", insertable = false, updatable = false)
    private Integer stageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"stageId\"")
    private Stage stage;

    @Column(name = "\"workspaceId\"", nullable = false, insertable = false, updatable = false)
    private Integer workspaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"workspaceId\"", nullable = false)
    private Workspace workspace;

    @Column(name = "\"assigneeId\"", insertable = false, updatable = false)
    private Integer assigneeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"assigneeId\"")
    private User assignee;

    @Column(name = "\"dueDate\"")
    private Instant dueDate;

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
