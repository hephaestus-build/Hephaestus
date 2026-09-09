import type { IdentityProviderView } from "@/api/types.gen";

/** Synthetic provider type the server emits for the optional passwordless dev sign-in. */
export const DEV_PROVIDER_TYPE = "DEV";
const SIGN_IN_PROVIDER_TYPES = new Set(["GITHUB", "GITLAB", "OIDC"]);

/**
 * OAuth sign-in capability, including institutional reauthentication from the authenticated catalog.
 * Slack and Outline only link to an existing account; dev sign-in uses its own username form.
 * Unknown provider types fail closed rather than becoming a sign-in button automatically.
 */
export function isSignInProvider(provider: IdentityProviderView): boolean {
	const type = provider.providerType?.toUpperCase();
	return SIGN_IN_PROVIDER_TYPES.has(type ?? "");
}
