package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.LanguageRequest;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final MessageService messageService;

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        User user = userRepository.findById(userId.intValue())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));

        return Map.of(
            "id", user.getId(),
            "name", user.getName(),
            "email", user.getEmail(),
            "language", user.getLanguage() != null ? user.getLanguage() : "pt-BR",
            "onboardingCompleted", user.getOnboardingCompleted() != null && user.getOnboardingCompleted()
        );
    }

    @PutMapping("/onboarding-complete")
    public ResponseEntity<Void> completeOnboarding(Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        User user = userRepository.findById(userId.intValue())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));
        user.setOnboardingCompleted(true);
        userRepository.save(user);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/name")
    public ResponseEntity<Void> updateName(@RequestBody Map<String, String> body, Authentication auth) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Number userId = (Number) auth.getPrincipal();
        User user = userRepository.findById(userId.intValue())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));
        user.setName(name.strip());
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/language")
    public ResponseEntity<Void> updateLanguage(@Valid @RequestBody LanguageRequest request, Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        User user = userRepository.findById(userId.intValue())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));
        user.setLanguage(request.language());
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }
}
