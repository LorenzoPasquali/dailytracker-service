package com.dailytracker.api.i18n;

import com.dailytracker.api.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class UserAwareLocaleResolver implements LocaleResolver {

    private static final Locale DEFAULT_LOCALE = Locale.of("pt", "BR");
    private static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.of("pt", "BR"),
            Locale.of("en", "US"),
            Locale.of("es")
    );

    private final UserRepository userRepository;

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Number userId) {
            try {
                return userRepository.findById(userId.intValue())
                        .map(user -> parseLocale(user.getLanguage()))
                        .orElse(resolveFromHeader(request));
            } catch (Exception e) {
                return resolveFromHeader(request);
            }
        }
        return resolveFromHeader(request);
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("Use PUT /api/user/language to change locale.");
    }

    private Locale resolveFromHeader(HttpServletRequest request) {
        String acceptLang = request.getHeader("Accept-Language");
        if (acceptLang == null || acceptLang.isBlank()) {
            return DEFAULT_LOCALE;
        }
        List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLang);
        Locale match = Locale.lookup(ranges, SUPPORTED_LOCALES);
        return match != null ? match : DEFAULT_LOCALE;
    }

    private Locale parseLocale(String language) {
        if (language == null) return DEFAULT_LOCALE;
        return switch (language) {
            case "pt-BR" -> Locale.of("pt", "BR");
            case "en-US" -> Locale.of("en", "US");
            case "es" -> Locale.of("es");
            default -> DEFAULT_LOCALE;
        };
    }
}
