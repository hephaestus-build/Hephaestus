import type { QueryClient } from "@tanstack/react-query";

import { apiBasePath } from "@/runtime/api-base-path";
import { currentUserQueryOptions, serverUnreachable } from "@/runtime/auth/guard";
import { safeReturnTo } from "@/runtime/auth/return-to";
import {
	refreshAccessToken,
	sessionRefreshedRecently,
	SESSION_COOLDOWN_MS,
} from "@/runtime/auth/session-refresh";
import { workspaceAddressConfig, workspaceReturnTo } from "@/runtime/workspace-address";

// Identity probes can return 401 anonymously; auth requests must not trigger recursive recovery.
function isExemptFromSessionExpiry(pathname: string, url: string): boolean {
	let requestPath = url;
	try {
		requestPath = new URL(url, window.location.origin).pathname;
	} catch {
		// Match the original path if URL parsing fails.
	}
	const base = apiBasePath();
	if (base && requestPath.startsWith(`${base}/`)) {
		requestPath = requestPath.slice(base.length);
	}
	if (requestPath === "/user") {
		return true;
	}
	if (requestPath.startsWith("/auth/")) {
		return true;
	}
	if (pathname === "/login" || pathname.startsWith("/auth/")) {
		return true;
	}
	return false;
}

export function handlePossibleSessionExpiry(response: Response, queryClient: QueryClient): boolean {
	if (response.status !== 401) {
		return false;
	}

	const { pathname } = window.location;
	if (isExemptFromSessionExpiry(pathname, response.url)) {
		return false;
	}
	void recoverOrLogout(queryClient, window.location.pathname + window.location.search);
	return true;
}

let recovery: Promise<void> | null = null;
let identityCheckedAt = 0;

async function recoverOrLogout(queryClient: QueryClient, currentPath: string): Promise<void> {
	recovery ??= doRecoverOrLogout(queryClient, currentPath).finally(() => {
		recovery = null;
	});
	return recovery;
}

/** Reads the identity now: a 401 from a request that left before the last renewal is late. */
async function readSession(
	queryClient: QueryClient,
): Promise<"signed-in" | "refused" | "unavailable"> {
	try {
		await queryClient.query({ ...currentUserQueryOptions(), staleTime: 0 });
		return "signed-in";
	} catch (error) {
		return serverUnreachable(error) ? "unavailable" : "refused";
	}
}

/**
 * A refused renewal can be late: another tab, or another workspace address that shares the cookie
 * but not the lock, renewed first, and its successor is already in use. Only a refused identity
 * ends the session.
 */
export async function sessionRefused(queryClient: QueryClient): Promise<boolean> {
	return (await readSession(queryClient)) === "refused";
}

// Every renewal rotates the cookie, so recovery renews only when the current cookie is refused.
async function doRecoverOrLogout(queryClient: QueryClient, currentPath: string): Promise<void> {
	// Identity probes are throttled within this tab; they never suppress a keep-alive renewal.
	// oxlint-disable-next-line no-restricted-properties -- Recovery throttling uses wall-clock time.
	if (sessionRefreshedRecently() || Date.now() - identityCheckedAt < SESSION_COOLDOWN_MS) {
		return;
	}
	const session = await readSession(queryClient);
	if (session === "unavailable") {
		return;
	}
	if (session === "refused") {
		const result = await refreshAccessToken();
		if (result === "unavailable") {
			return;
		}
		if (result === "expired") {
			if (await sessionRefused(queryClient)) {
				redirectToLogin(currentPath);
				return;
			}
		}
	} else {
		// oxlint-disable-next-line no-restricted-properties -- Recovery throttling uses wall-clock time.
		identityCheckedAt = Date.now();
	}
	// Refetch queries, but never replay mutations after an ambiguous failure.
	void queryClient.invalidateQueries();
}

export function redirectToLogin(
	currentPath = window.location.pathname + window.location.search,
): void {
	const target = new URL(
		"/login",
		workspaceAddressConfig.enabled ? workspaceAddressConfig.apexOrigin : window.location.origin,
	);
	target.searchParams.set("returnTo", workspaceReturnTo(safeReturnTo(currentPath)));
	window.location.assign(target.toString());
}

export function __resetSessionRecoveryForTests(): void {
	recovery = null;
	identityCheckedAt = 0;
}
