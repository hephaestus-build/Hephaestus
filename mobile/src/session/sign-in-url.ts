/** The browser address that starts a native sign-in with `provider`, or the dev sign-in for `username`. */
export function nativeSignInUrl(
	apiBaseUrl: string,
	method: { provider: string } | { devUsername: string },
	pkce: { challenge: string; state: string },
	redirectUri: string,
): string {
	const params = new URLSearchParams();
	const path =
		"provider" in method
			? (params.set("provider", method.provider), "/auth/login/native")
			: (params.set("username", method.devUsername), "/auth/dev-login/native");
	params.set("code_challenge", pkce.challenge);
	params.set("code_challenge_method", "S256");
	params.set("state", pkce.state);
	params.set("redirect_uri", redirectUri);
	return `${apiBaseUrl}${path}?${params.toString()}`;
}

export type SignInCallback =
	| { kind: "code"; code: string }
	| { kind: "error"; error: string }
	| { kind: "foreign" };

/**
 * Reads the redirect that ended a sign-in. Anything not addressed to this app's callback, or carrying a
 * `state` other than the one this sign-in sent, is `foreign`: it is not ours to act on, whoever sent it.
 */
export function parseSignInCallback(
	url: string,
	redirectUri: string,
	state: string,
): SignInCallback {
	if (!url.startsWith(`${redirectUri}?`)) {
		return { kind: "foreign" };
	}
	const params = new URLSearchParams(url.slice(redirectUri.length + 1));
	if (params.get("state") !== state) {
		return { kind: "foreign" };
	}
	const code = params.get("code");
	if (code !== null && code !== "") {
		return { kind: "code", code };
	}
	return { kind: "error", error: params.get("error") ?? "unknown" };
}

const SIGN_IN_ERRORS: Record<string, string | null> = {
	// The person cancelled at the identity provider; nothing to say.
	access_denied: null,
	account_inactive:
		"This account is suspended or being deleted. An administrator of this Hephaestus can restore it.",
	link_requires_auth:
		"That provider only links to an existing account. Sign in with GitHub or GitLab instead.",
	identity_already_linked: "That identity belongs to another Hephaestus account.",
	unknown_provider:
		"That sign-in option is no longer offered by this Hephaestus. Pick another one.",
};

/** What a person reads when the server ended the sign-in with `error`; null when they cancelled. */
export function signInErrorMessage(error: string): string | null {
	const message = SIGN_IN_ERRORS[error];
	return message === undefined ? "Signing in did not finish. Try again." : message;
}
