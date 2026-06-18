package com.dailytracker.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A per-user credential that lets an external MCP client (e.g. Claude Desktop) act on behalf of the
 * owning user. Only the SHA-256 hash of the token is persisted; the plaintext is returned once at
 * creation and never stored. Tokens are revocable and may carry an expiry and a read-only flag.
 */
@Entity
@Table(name = "\"McpAccessToken\"", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"userId\"", nullable = false)
    private User user;

    @Column(name = "\"tokenHash\"", nullable = false, unique = true)
    private String tokenHash;

    @Column
    private String label;

    @Column(name = "\"readOnly\"", nullable = false)
    @Builder.Default
    private Boolean readOnly = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean revoked = false;

    @Column(name = "\"createdAt\"", nullable = false)
    private Instant createdAt;

    @Column(name = "\"lastUsedAt\"")
    private Instant lastUsedAt;

    @Column(name = "\"expiresAt\"")
    private Instant expiresAt;
}
