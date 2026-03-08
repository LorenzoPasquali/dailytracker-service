package com.dailytracker.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LanguageRequest(
        @NotBlank @Pattern(regexp = "pt-BR|en-US|es") String language
) {}
