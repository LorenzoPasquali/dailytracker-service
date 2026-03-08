package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "\"TaskType\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    @Column(name = "\"projectId\"", nullable = false, insertable = false, updatable = false)
    private Integer projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"projectId\"", nullable = false)
    private Project project;

    @OneToMany(mappedBy = "taskType")
    private List<Task> tasks;

    @PreRemove
    private void preRemove() {
        if (tasks != null) {
            tasks.forEach(task -> task.setTaskType(null));
        }
    }
}
