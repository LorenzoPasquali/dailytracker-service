package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "\"User\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false, length = 20)
    private String name;

    @Column
    private String password;

    @Column(name = "\"googleId\"", unique = true)
    private String googleId;

    @Column(name = "\"geminiKey\"")
    private String geminiKey;

    @Column(name = "\"language\"", nullable = false)
    @Builder.Default
    private String language = "pt-BR";

    @Column(name = "\"onboardingCompleted\"", nullable = false)
    @Builder.Default
    private Boolean onboardingCompleted = false;

    @OneToMany(mappedBy = "user")
    private List<Task> tasks;

    @OneToMany(mappedBy = "user")
    private List<Project> projects;
}
