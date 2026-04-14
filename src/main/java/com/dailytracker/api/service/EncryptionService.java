package com.dailytracker.api.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption service.
 *
 * The symmetric key is derived from the configured secret using PBKDF2-HMAC-SHA256
 * with 600_000 iterations (OWASP 2023 recommendation). A fixed application salt is
 * used so that the derivation is deterministic across restarts — the threat model
 * here is protecting at-rest data if the DB is leaked, assuming AES_SECRET remains
 * confidential; rainbow tables aren't relevant since the secret is not a password.
 *
 * Ciphertext layout: [12-byte IV][ciphertext || 16-byte GCM tag], base64 encoded.
 *
 * IMPORTANT: Changing AES_SECRET, the salt, the KDF parameters, or the cipher
 * algorithm invalidates all previously encrypted data stored in the database
 * (e.g. User.geminiKey). A data migration is required if any ciphertext exists.
 */
@Service
public class EncryptionService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int AES_KEY_BITS = 256;
    private static final byte[] KDF_SALT =
            "dailytracker::aes-kdf::v1".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.aes-secret}")
    private String aesSecret;

    private SecretKeySpec keySpec;

    @PostConstruct
    void init() {
        if (aesSecret == null || aesSecret.isBlank()) {
            throw new IllegalStateException("AES_SECRET must be configured.");
        }
        if (aesSecret.length() < 16) {
            throw new IllegalStateException(
                    "AES_SECRET must be at least 16 characters for adequate entropy."
            );
        }
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(
                    aesSecret.toCharArray(),
                    KDF_SALT,
                    PBKDF2_ITERATIONS,
                    AES_KEY_BITS
            );
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            this.keySpec = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive AES key from AES_SECRET.", e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[GCM_IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, GCM_IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao criptografar.", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < GCM_IV_LENGTH + (GCM_TAG_LENGTH_BITS / 8)) {
                throw new IllegalArgumentException("Ciphertext too short.");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao descriptografar.", e);
        }
    }
}
