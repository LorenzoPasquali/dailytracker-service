package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.ChatRequest;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.repository.UserRepository;
import com.dailytracker.api.service.EncryptionService;
import com.dailytracker.api.service.GeminiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final GeminiService geminiService;

    @GetMapping("/key-status")
    public Map<String, Object> keyStatus(Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        return Map.of("hasKey", user.getGeminiKey() != null && !user.getGeminiKey().isBlank());
    }

    @PutMapping("/key")
    public ResponseEntity<Void> saveKey(@RequestBody Map<String, String> body, Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        String key = body.get("key");
        if (key == null || key.isBlank()) {
            throw new BadRequestException("A chave da API não pode ser vazia.");
        }
        user.setGeminiKey(encryptionService.encrypt(key));
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/key")
    public ResponseEntity<Void> deleteKey(Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        user.setGeminiKey(null);
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@Valid @RequestBody ChatRequest request, Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        if (user.getGeminiKey() == null || user.getGeminiKey().isBlank()) {
            throw new BadRequestException("Chave Gemini não configurada. Configure sua API Key para usar o assistente.");
        }
        String decryptedKey;
        try {
            decryptedKey = encryptionService.decrypt(user.getGeminiKey());
        } catch (RuntimeException e) {
            throw new BadRequestException("Erro ao recuperar sua chave. Reconfigure sua API Key.");
        }
        String reply = geminiService.chat(decryptedKey, request.history(), userId);
        return Map.of("reply", reply);
    }
}
