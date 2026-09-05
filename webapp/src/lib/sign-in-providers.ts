import type { IdentityProviderView } from "@/api/types.gen";

/** Synthetic provider type the server emits for the optional passwordless dev sign-in. */
export const DEV_PROVIDER_TYPE = "DEV";
const LINK_ONLY_PROVIDER_TYPES = new Set(["SLACK", "OUTLINE"]);

/**
 * Whether an advertised provider is a way *in*. `/identity-providers` also lists providers that can
 * only be attached to a session that already exists (Slack, Outline) and the dev sign-in, which
 * needs a username field rather than an OAuth redirect — an OAuth-style button for any of them
 * leads to a path that cannot authenticate anybody.
 */
export function isSignInProvider(provider: IdentityProviderView): boolean {
	const type = provider.providerType?.toUpperCase();
	return type !== DEV_PROVIDER_TYPE && !LINK_ONLY_PROVIDER_TYPES.has(type ?? "");
}
