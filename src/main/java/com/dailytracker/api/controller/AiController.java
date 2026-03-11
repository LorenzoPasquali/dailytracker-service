package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.ChatRequest;
import com.dailytracker.api.dto.response.ChatResponse;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.entity.Workspace;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.exception.ForbiddenException;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.UserRepository;
import com.dailytracker.api.repository.WorkspaceRepository;
import com.dailytracker.api.service.EncryptionService;
import com.dailytracker.api.service.GeminiService;
import com.dailytracker.api.service.WorkspaceService;
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
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final EncryptionService encryptionService;
    private final GeminiService geminiService;
    private final MessageService messageService;

    @GetMapping("/key-status")
    public Map<String, Object> keyStatus(
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        assertPersonalWorkspace(workspaceId, userId.intValue());
        User user = userRepository.findById(userId.intValue())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));
        return Map.of("hasKey", user.getGeminiKey() != null && !user.getGeminiKey().isBlank());
    }

    @PutMapping("/key")
    public ResponseEntity<Void> saveKey(@RequestBody Map<String, String> body, Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        User user = userRepository.findById(userId.intValue())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));
        String key = body.get("key");
        if (key == null || key.isBlank()) {
            throw new BadRequestException(messageService.get("error.gemini.key.empty"));
        }
        user.setGeminiKey(encryptionService.encrypt(key));
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/key")
    public ResponseEntity<Void> deleteKey(Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        User user = userRepository.findById(userId.intValue())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));
        user.setGeminiKey(null);
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        int wsId = workspaceId != null ? workspaceId : workspaceService.getPersonalWorkspaceId(userId.intValue());
        assertPersonalWorkspace(wsId, userId.intValue());
        User user = userRepository.findById(userId.intValue())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));
        if (user.getGeminiKey() == null || user.getGeminiKey().isBlank()) {
            throw new BadRequestException(messageService.get("error.gemini.key.missing"));
        }
        String decryptedKey;
        try {
            decryptedKey = encryptionService.decrypt(user.getGeminiKey());
        } catch (RuntimeException e) {
            throw new BadRequestException(messageService.get("error.gemini.key.decrypt"));
        }
        return geminiService.chat(decryptedKey, request.history(), userId.intValue(), wsId, user.getLanguage());
    }

    private void assertPersonalWorkspace(Integer workspaceId, int userId) {
        if (workspaceId == null) return;
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.workspace.not_found")));
        if (!Boolean.TRUE.equals(workspace.getIsPersonal())) {
            throw new ForbiddenException(messageService.get("error.workspace.ai.shared"));
        }
    }
}
