package com.dailytracker.api.mcp;

/**
 * Holds the authenticated principal for the duration of an MCP tool invocation.
 *
 * <p>The user a tool acts on is <strong>never</strong> taken from a tool argument supplied by the
 * LLM — it is resolved out-of-band and stored here, so an AI cannot read or write another user's
 * data by passing a different id. The context is populated by:
 * <ul>
 *   <li>the internal Gemini path ({@code GeminiService}) before running the tool-call loop;</li>
 *   <li>(Fase 2) the {@code /mcp} servlet filter after validating a per-user MCP token.</li>
 * </ul>
 *
 * <p>{@code workspaceId} is the default workspace tools fall back to when the caller omits one; it
 * is always re-checked with {@code WorkspaceService.assertMember} inside each tool.
 */
public final class McpPrincipalContext {

    public record Principal(Integer userId, Integer workspaceId, boolean readOnly) {}

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private McpPrincipalContext() {}

    /** Internal callers (Gemini) always have write access. */
    public static void set(Integer userId, Integer workspaceId) {
        set(userId, workspaceId, false);
    }

    public static void set(Integer userId, Integer workspaceId, boolean readOnly) {
        HOLDER.set(new Principal(userId, workspaceId, readOnly));
    }

    /** True when the current credential is a read-only MCP token. */
    public static boolean isReadOnly() {
        Principal p = HOLDER.get();
        return p != null && p.readOnly();
    }

    public static Integer requireUserId() {
        Principal p = HOLDER.get();
        if (p == null || p.userId() == null) {
            throw new IllegalStateException("No MCP principal in context");
        }
        return p.userId();
    }

    /** Default workspace for the current invocation, or {@code null} if none was set. */
    public static Integer workspaceId() {
        Principal p = HOLDER.get();
        return p != null ? p.workspaceId() : null;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
