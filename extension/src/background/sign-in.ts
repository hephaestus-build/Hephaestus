import { browser } from "@wxt-dev/browser";

import { exchangeClientSignIn } from "~/api/sdk.gen";
import { clientFor } from "~/background/api";
import { network, server, WorkerError } from "~/background/errors";
import { type Credentials, tokensSchema } from "~/background/session";
import type { InstanceConfig } from "~/background/storage";
import { randomToken, s256Challenge } from "~/shared/pkce";

export type SignInMethod =
	| { kind: "provider"; registrationId: string }
	| { kind: "dev"; username: string; admin: boolean };

/** The one redirect the server has registered for this extension id. */
export function redirectUri(): string {
	return browser.identity.getRedirectURL("callback");
}

export function signInUrl(
	instance: InstanceConfig,
	method: SignInMethod,
	params: { clientId: string; redirectUri: string; challenge: string; state: string },
): string {
	const url =
		method.kind === "provider"
			? new URL(`${instance.apiBase}/auth/login`)
			: new URL(`${instance.apiBase}/auth/dev-login/client`);
	if (method.kind === "provider") {
		url.searchParams.set("provider", method.registrationId);
		url.searchParams.set("mode", "client");
	} else {
		url.searchParams.set("username", method.username);
		url.searchParams.set("admin", String(method.admin));
	}
	url.searchParams.set("client_id", params.clientId);
	url.searchParams.set("redirect_uri", params.redirectUri);
	url.searchParams.set("code_challenge", params.challenge);
	url.searchParams.set("code_challenge_method", "S256");
	url.searchParams.set("state", params.state);
	return url.toString();
}

/**
 * Reads the handoff code out of the callback Chrome returns, accepting it only at the exact redirect
 * and with the exact `state` this attempt sent. Anything else — another path, a reflected error, a
 * second parameter set — is refused rather than guessed at.
 */
export function readCallback(
	responseUrl: string,
	expected: { redirectUri: string; state: string },
): { code: string } | { error: string } {
	let url: URL;
	try {
		url = new URL(responseUrl);
	} catch {
		return { error: "invalid" };
	}
	const redirect = new URL(expected.redirectUri);
	if (url.origin !== redirect.origin || url.pathname !== redirect.pathname) {
		return { error: "invalid" };
	}
	const params = url.searchParams;
	if (params.getAll("state").length !== 1 || params.get("state") !== expected.state) {
		return { error: "state" };
	}
	const code = params.get("code");
	if (params.getAll("code").length !== 1 || code === null || !/^[A-Za-z0-9_-]{43}$/u.test(code)) {
		return { error: params.get("error") ?? "invalid" };
	}
	return { code };
}

/**
 * One interactive sign-in, from the worker, started by a click in the options page. The verifier
 * and `state` live only in this call. Chrome closing the window, or the user cancelling, is an
 * ordinary outcome and says so.
 */
export async function signIn(instance: InstanceConfig, method: SignInMethod): Promise<Credentials> {
	const verifier = randomToken();
	const state = randomToken();
	const clientId = browser.runtime.id;
	const redirect = redirectUri();
	const url = signInUrl(instance, method, {
		clientId,
		redirectUri: redirect,
		challenge: await s256Challenge(verifier),
		state,
	});
	let responseUrl: string | undefined;
	try {
		responseUrl = await browser.identity.launchWebAuthFlow({ url, interactive: true });
	} catch (error) {
		// Chrome uses this exact error when the user closes or declines the interactive flow.
		// Other failures can contain an authorization URL; never pass their raw message to a view.
		if (error instanceof Error && error.message === "The user did not approve access.") {
			throw new WorkerError("cancelled", "Sign-in was cancelled before it finished.");
		}
		throw new WorkerError("network", "Sign-in could not complete. Try again.");
	}
	if (responseUrl === undefined) {
		throw new WorkerError("invalid", "Sign-in could not complete. Try again.");
	}
	const callback = readCallback(responseUrl, { redirectUri: redirect, state });
	if ("error" in callback) {
		throw new WorkerError(
			"invalid",
			callback.error === "access_denied"
				? "Sign-in was declined at the provider."
				: "Sign-in did not complete. Try again.",
		);
	}
	let result: { data?: unknown; response?: Response };
	try {
		result = await exchangeClientSignIn({
			client: clientFor(instance.apiBase),
			body: { clientId, redirectUri: redirect, code: callback.code, codeVerifier: verifier },
		});
	} catch {
		throw network();
	}
	const tokens = tokensSchema.safeParse(result.data);
	if (!tokens.success) {
		throw server(result.response?.status ?? 0);
	}
	return { issuer: instance, tokens: tokens.data };
}
