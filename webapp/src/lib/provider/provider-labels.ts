/**
 * The one place a provider *type* becomes human-readable text.
 *
 * The server emits provider types as SCREAMING_CASE enum names (`GITHUB`, `OUTLINE`, …). Those are
 * wire values, never user-facing copy — every surface that shows a provider to a person routes
 * through here so `OUTLINE` never leaks to a reader as `OUTLINE`.
 *
 * Distinct from `provider-terms.ts`, which is SCM-only vocabulary (PR vs MR, repo vs project).
 */
const PROVIDER_LABELS: Record<string, string> = {
	GITHUB: "GitHub",
	GITLAB: "GitLab",
	SLACK: "Slack",
	OUTLINE: "Outline",
	OIDC: "Organization account",
	KEYCLOAK_DIRECTORY: "Keycloak directory",
	DEV: "Dev sign-in",
};

/**
 * Human label for a provider type. Unknown types fall back to the raw value (better a wire name than
 * nothing); a missing type falls back to `fallback`, which prose can set to e.g. "that provider".
 */
export function getProviderLabel(providerType?: string | null, fallback = "that provider"): string {
	if (!providerType) return fallback;
	return PROVIDER_LABELS[providerType.toUpperCase()] ?? providerType;
}

export { PROVIDER_LABELS };
