package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "\"Stage\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String color = "#6b7280";

    @Column(nullable = false)
    private Integer position = 0;

    @Column(name = "\"isFinal\"", nullable = false)
    private Boolean isFinal = false;

    @Column(name = "\"workspaceId\"", nullable = false, insertable = false, updatable = false)
    private Integer workspaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"workspaceId\"", nullable = false)
    private Workspace workspace;
}
