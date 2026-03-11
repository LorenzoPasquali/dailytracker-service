package com.dailytracker.api.service;

import com.dailytracker.api.entity.User;
import com.dailytracker.api.entity.Workspace;
import com.dailytracker.api.entity.WorkspaceInvite;
import com.dailytracker.api.entity.WorkspaceMember;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.exception.ForbiddenException;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.UserRepository;
import com.dailytracker.api.repository.WorkspaceInviteRepository;
import com.dailytracker.api.repository.WorkspaceMemberRepository;
import com.dailytracker.api.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceInviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final WorkspaceEventPublisher eventPublisher;
    private final MessageService messageService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    // ── Queries ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllForUser(Integer userId) {
        return workspaceRepository.findAllByMemberUserId(userId)
                .stream()
                .map(w -> toResponse(w, userId))
                .toList();
    }

    @Transactional(readOnly = true)
    public Integer getPersonalWorkspaceId(Integer userId) {
        return workspaceRepository.findByCreatorIdAndIsPersonalTrue(userId)
                .map(Workspace::getId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageService.get("error.workspace.not_found")));
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Transactional
    public void createPersonalWorkspace(User user) {
        var workspace = Workspace.builder()
                .name("Personal")
                .creator(user)
                .isPersonal(true)
                .build();
        workspace = workspaceRepository.save(workspace);

        var member = WorkspaceMember.builder()
                .workspace(workspace)
                .user(user)
                .role("CREATOR")
                .build();
        memberRepository.save(member);
    }

    @Transactional
    public Map<String, Object> create(String name, Integer userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageService.get("error.user.not_found")));

        var workspace = Workspace.builder()
                .name(name)
                .creator(user)
                .isPersonal(false)
                .build();
        workspace = workspaceRepository.save(workspace);

        var member = WorkspaceMember.builder()
                .workspace(workspace)
                .user(user)
                .role("CREATOR")
                .build();
        memberRepository.save(member);

        return toResponse(workspace, userId);
    }

    @Transactional
    public Map<String, Object> update(Integer workspaceId, String name, Integer userId) {
        var workspace = getWorkspaceOrThrow(workspaceId);
        assertCreator(workspace, userId);

        if (Boolean.TRUE.equals(workspace.getIsPersonal())) {
            throw new BadRequestException(messageService.get("error.workspace.personal.rename"));
        }

        workspace.setName(name);
        return toResponse(workspaceRepository.save(workspace), userId);
    }

    @Transactional
    public void delete(Integer workspaceId, Integer userId) {
        var workspace = getWorkspaceOrThrow(workspaceId);
        assertCreator(workspace, userId);

        if (Boolean.TRUE.equals(workspace.getIsPersonal())) {
            throw new BadRequestException(messageService.get("error.workspace.personal.delete"));
        }

        workspaceRepository.delete(workspace);
    }

    // ── Invite ────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createInviteToken(Integer workspaceId, Integer userId) {
        var workspace = getWorkspaceOrThrow(workspaceId);
        assertCreator(workspace, userId);

        String token = UUID.randomUUID().toString().replace("-", "");
        var invite = WorkspaceInvite.builder()
                .workspace(workspace)
                .token(token)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        inviteRepository.save(invite);

        String url = frontendUrl + "/invite/" + token;
        return Map.of("token", token, "url", url);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getInvitePreview(String token) {
        var invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageService.get("error.workspace.invite.not_found")));

        if (invite.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException(messageService.get("error.workspace.invite.expired"));
        }

        var workspace = invite.getWorkspace();
        var creator = workspace.getCreator();
        return Map.of(
                "workspaceName", workspace.getName(),
                "creatorEmail", creator.getEmail()
        );
    }

    @Transactional
    public void acceptInvite(String token, Integer userId) {
        var invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageService.get("error.workspace.invite.not_found")));

        if (invite.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException(messageService.get("error.workspace.invite.expired"));
        }

        Integer workspaceId = invite.getWorkspaceId();

        if (memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            throw new BadRequestException(messageService.get("error.workspace.already_member"));
        }

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageService.get("error.user.not_found")));

        var member = WorkspaceMember.builder()
                .workspace(invite.getWorkspace())
                .user(user)
                .role("MEMBER")
                .build();
        memberRepository.save(member);
        eventPublisher.publishMemberEvent(workspaceId, "MEMBER_JOINED",
                Map.of("userId", userId, "name", user.getName(), "email", user.getEmail()));
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMembers(Integer workspaceId, Integer userId) {
        assertMember(workspaceId, userId);
        return memberRepository.findByWorkspaceId(workspaceId)
                .stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("userId", m.getUserId());
                    map.put("name", m.getUser().getName());
                    map.put("email", m.getUser().getEmail());
                    map.put("role", m.getRole());
                    map.put("joinedAt", m.getJoinedAt().toString());
                    return map;
                })
                .toList();
    }

    @Transactional
    public void removeMember(Integer workspaceId, Integer targetUserId, Integer requestingUserId) {
        var workspace = getWorkspaceOrThrow(workspaceId);

        if (targetUserId.equals(requestingUserId)) {
            // Any member can leave
            assertMember(workspaceId, requestingUserId);
            if (workspace.getCreatorId().equals(requestingUserId)) {
                throw new BadRequestException(messageService.get("error.workspace.creator.only"));
            }
        } else {
            // Only creator can remove others
            assertCreator(workspace, requestingUserId);
        }

        memberRepository.deleteByWorkspaceIdAndUserId(workspaceId, targetUserId);
        eventPublisher.publishMemberEvent(workspaceId, "MEMBER_LEFT",
                Map.of("userId", targetUserId));
    }

    // ── Authorization helpers ─────────────────────────────────────────────────

    public void assertMember(Integer workspaceId, Integer userId) {
        if (!memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            throw new ForbiddenException(messageService.get("error.workspace.forbidden"));
        }
    }

    private void assertCreator(Workspace workspace, Integer userId) {
        assertMember(workspace.getId(), userId);
        if (!workspace.getCreatorId().equals(userId)) {
            throw new ForbiddenException(messageService.get("error.workspace.creator.only"));
        }
    }

    private Workspace getWorkspaceOrThrow(Integer workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageService.get("error.workspace.not_found")));
    }

    // ── Response mapper ───────────────────────────────────────────────────────

    private Map<String, Object> toResponse(Workspace workspace, Integer requestingUserId) {
        var member = memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), requestingUserId);
        String role = member.map(WorkspaceMember::getRole).orElse("MEMBER");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", workspace.getId());
        map.put("name", workspace.getName());
        map.put("isPersonal", workspace.getIsPersonal());
        map.put("creatorId", workspace.getCreatorId());
        map.put("role", role);
        map.put("createdAt", workspace.getCreatedAt().toString());
        return map;
    }
}
