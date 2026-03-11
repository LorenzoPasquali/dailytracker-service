package com.dailytracker.api.config;

import com.dailytracker.api.repository.WorkspaceMemberRepository;
import com.dailytracker.api.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwtService;
    private final WorkspaceMemberRepository memberRepository;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor == null) return message;

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String token = extractToken(accessor);
                    if (token == null || !jwtService.isTokenValid(token)) {
                        throw new org.springframework.security.access.AccessDeniedException(
                                "Invalid or missing JWT token.");
                    }
                    Integer userId = jwtService.extractUserId(token);
                    accessor.setUser(() -> userId.toString());
                }

                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    String destination = accessor.getDestination();
                    if (destination != null && destination.startsWith("/topic/workspace/")) {
                        String[] parts = destination.split("/");
                        if (parts.length >= 4) {
                            try {
                                Integer workspaceId = Integer.parseInt(parts[3]);
                                Integer userId = extractUserIdFromAccessor(accessor);
                                if (userId == null || !memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
                                    throw new org.springframework.security.access.AccessDeniedException(
                                            "Not a member of workspace " + workspaceId);
                                }
                            } catch (NumberFormatException e) {
                                throw new org.springframework.security.access.AccessDeniedException(
                                        "Invalid workspace ID in destination.");
                            }
                        }
                    }
                }

                return message;
            }
        });
    }

    private String extractToken(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // Also support token passed as a native header directly
        return accessor.getFirstNativeHeader("token");
    }

    private Integer extractUserIdFromAccessor(StompHeaderAccessor accessor) {
        if (accessor.getUser() != null) {
            try {
                return Integer.parseInt(accessor.getUser().getName());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
