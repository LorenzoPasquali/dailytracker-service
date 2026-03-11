package com.dailytracker.api.security;

import com.dailytracker.api.entity.RefreshToken;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.repository.UserRepository;
import com.dailytracker.api.service.AuthService;
import com.dailytracker.api.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.PrintWriter;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthService authService;
    private final WorkspaceService workspaceService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String googleId = oAuth2User.getAttribute("sub");
        String email = oAuth2User.getAttribute("email");
        String googleName = oAuth2User.getAttribute("given_name") != null
                ? oAuth2User.getAttribute("given_name")
                : (email != null ? email.split("@")[0] : "User");

        User user = userRepository.findByGoogleId(googleId)
                .orElseGet(() -> {
                    // Se nao encontrar pelo Google ID, tenta encontrar pelo Email (para vincular contas)
                    return userRepository.findByEmail(email)
                            .map(existingUser -> {
                                existingUser.setGoogleId(googleId);
                                return userRepository.save(existingUser);
                            })
                            .orElseGet(() -> {
                                User newUser = userRepository.save(
                                        User.builder()
                                                .googleId(googleId)
                                                .email(email)
                                                .name(googleName)
                                                .build()
                                );
                                workspaceService.createPersonalWorkspace(newUser);
                                return newUser;
                            });
                });

        String token = jwtService.generateToken(user.getId());
        RefreshToken refreshToken = authService.createRefreshToken(user);

        // Replicate the Node.js popup flow: postMessage to opener, fallback to redirect
        response.setContentType("text/html");
        PrintWriter writer = response.getWriter();
        writer.write("<!DOCTYPE html><html><body><script>"
                + "if (window.opener) {"
                + "  window.opener.postMessage({ token: '" + token + "', refreshToken: '" + refreshToken.getToken() + "' }, '" + frontendUrl + "');"
                + "  window.close();"
                + "} else {"
                + "  window.location.href = '" + frontendUrl + "/login/success?token=" + token + "&refreshToken=" + refreshToken.getToken() + "';"
                + "}"
                + "</script></body></html>");
        writer.flush();
    }
}
