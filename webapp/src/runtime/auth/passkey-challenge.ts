import type { AnyRouter } from "@tanstack/react-router";

import { client } from "@/api/client.gen";
import { isProblemType } from "@/lib/problem-detail";
import { safeReturnTo } from "@/runtime/auth/return-to";

/** The focused page that asks for a passkey; an apex path in `workspace-address`. */
export const PASSKEY_CHALLENGE_PATH = "/verify-passkey";

/** Where a confirmed challenge goes: a safe local path, never the challenge itself. */
export function passkeyReturnTo(returnTo: string | undefined): string {
	const target = safeReturnTo(returnTo);
	const path = target.split(/[?#]/u, 1)[0];
	return path === PASSKEY_CHALLENGE_PATH ? "/" : target;
}

/**
 * Where leaving a challenge goes when there is no earlier page to go back to: the home of the
 * workspace it was asked from, never the refused page, else home.
 */
export function passkeyLeaveTo(returnTo: string | undefined): string {
	const path = passkeyReturnTo(returnTo).split(/[?#]/u, 1)[0] ?? "/";
	const workspaceSlug = /^\/w\/(?<slug>[^/]+)/u.exec(path)?.groups?.slug;
	return workspaceSlug === undefined ? "/" : `/w/${workspaceSlug}`;
}

// Settings manages passkeys in place, and the challenge page is where the refusal would lead.
function handlesItsOwnRefusals(pathname: string): boolean {
	return pathname === "/settings" || pathname === PASSKEY_CHALLENGE_PATH;
}

/**
 * Handles `passkey-required` refusals. Concurrent refusals share one navigation, such as one that a
 * draft blocker holds. The navigation belongs to one installation, so a navigation its router never
 * finishes cannot hold another router's responses.
 */
export function passkeyChallengeHandler() {
	let navigation: Promise<void> | undefined;
	return async (response: Response, navigate: () => Promise<void>): Promise<void> => {
		if (response.status !== 403) {
			return;
		}
		const body: unknown = await response
			.clone()
			.json()
			.catch(() => undefined);
		if (isProblemType(body, "passkey-required")) {
			navigation ??= navigate().finally(() => {
				navigation = undefined;
			});
			await navigation;
		}
	};
}

/**
 * Takes the developer to confirm with a passkey when the server refuses a request of the page they
 * are on with `passkey-required`, and back to that exact page once they have. A response from a page
 * they left cannot interrupt their current page. The challenge replaces the refused page in history,
 * so Back does not ask again. The refused request is not sent again. Returns the function that
 * removes the handling.
 */
export function installPasskeyChallenge(router: AnyRouter): () => void {
	// Keyed by the request's options, the one object that the request and response interceptors share.
	const madeOn = new WeakMap<object, string>();
	const handlePasskeyChallenge = passkeyChallengeHandler();
	const recording = client.interceptors.request.use((request, options) => {
		madeOn.set(options, router.state.location.href);
		return request;
	});
	const handling = client.interceptors.response.use(async (response, _request, options) => {
		const here = router.state.location;
		if (madeOn.get(options) === here.href && !handlesItsOwnRefusals(here.pathname)) {
			await handlePasskeyChallenge(response, async () => {
				// Fetch resolves at the headers; the page may change while the error body arrives.
				const current = router.state.location;
				if (madeOn.get(options) === current.href && !handlesItsOwnRefusals(current.pathname)) {
					// The router's own href: the workspace rewrite maps it back to a workspace address.
					await router.navigate({
						to: PASSKEY_CHALLENGE_PATH,
						search: { returnTo: current.href },
						replace: true,
					});
				}
			});
		}
		return response;
	});
	return () => {
		client.interceptors.request.eject(recording);
		client.interceptors.response.eject(handling);
	};
}
