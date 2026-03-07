package com.dailytracker.api.security;

import com.dailytracker.api.entity.User;
import com.dailytracker.api.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtService jwtService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String googleId = oAuth2User.getAttribute("sub");
        String email = oAuth2User.getAttribute("email");

        User user = userRepository.findByGoogleId(googleId)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .googleId(googleId)
                                .email(email)
                                .build()
                ));

        String token = jwtService.generateToken(user.getId());

        // Replicate the Node.js popup flow: postMessage to opener, fallback to redirect
        response.setContentType("text/html");
        PrintWriter writer = response.getWriter();
        writer.write("<!DOCTYPE html><html><body><script>"
                + "if (window.opener) {"
                + "  window.opener.postMessage({ token: '" + token + "' }, '" + frontendUrl + "');"
                + "  window.close();"
                + "} else {"
                + "  window.location.href = '" + frontendUrl + "/login/success?token=" + token + "';"
                + "}"
                + "</script></body></html>");
        writer.flush();
    }
}
