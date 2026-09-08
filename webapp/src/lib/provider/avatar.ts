import type { ProviderType } from "./provider-terms";

/**
 * Returns the avatar URL for a workspace (organization/group) account.
 *
 * - GitHub: public avatar via `github.com/<login>.png`
 * - GitLab / non-SCM providers (e.g. SLACK, OUTLINE): avatars are instance-specific and must come
 *   from the API. Returns `null` so callers render a fallback (e.g. initials).
 */
export function getWorkspaceAvatarUrl(
	provider: ProviderType | "SLACK" | "OUTLINE" | "OIDC",
	accountLogin: string,
): string | null {
	if (provider === "GITHUB") {
		return `https://github.com/${encodeURIComponent(accountLogin)}.png`;
	}
	// GitLab avatar URLs are instance-specific; the backend should provide them. Non-SCM providers
	// (SLACK, OUTLINE) never back a workspace's SCM avatar, so fall back to initials rather than a
	// bogus link.
	return null;
}

/**
 * Returns the avatar URL for a team.
 *
 * - GitHub: public avatar via `avatars.githubusercontent.com/t/<id>`
 * - GitLab: returns `null` (fallback to initials).
 */
export function getTeamAvatarUrl(provider: ProviderType, teamId: number): string | null {
	if (provider === "GITHUB") {
		return `https://avatars.githubusercontent.com/t/${teamId}?s=512&v=4`;
	}
	return null;
}
