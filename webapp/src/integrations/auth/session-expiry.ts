import type { QueryClient } from "@tanstack/react-query";

import environment from "@/environment";
import { safeReturnTo } from "@/integrations/auth/guard";
import { refreshAccessToken } from "@/integrations/auth/session-refresh";

function apiBasePath(): string {
	try {
		return new URL(environment.serverUrl, window.location.origin).pathname.replace(/\/$/, "");
	} catch {
		return "";
	}
}

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
	if (requestPath === "/user") return true;
	if (requestPath.startsWith("/auth/")) return true;
	if (pathname === "/login" || pathname.startsWith("/auth/")) return true;
	return false;
}

export function handlePossibleSessionExpiry(response: Response, queryClient: QueryClient): boolean {
	if (response.status !== 401) return false;

	const pathname = window.location.pathname;
	if (isExemptFromSessionExpiry(pathname, response.url)) return false;
	void recoverOrLogout(queryClient, window.location.pathname + window.location.search);
	return true;
}

// Late responses may still reject the cookie that a successful renewal replaced.
const REFRESH_RECOVERY_COOLDOWN_MS = 10_000;

let recovery: Promise<void> | null = null;
let lastRefreshRecoveryAt = 0;

function recoverOrLogout(queryClient: QueryClient, currentPath: string): Promise<void> {
	recovery ??= doRecoverOrLogout(queryClient, currentPath).finally(() => {
		recovery = null;
	});
	return recovery;
}

async function doRecoverOrLogout(queryClient: QueryClient, currentPath: string): Promise<void> {
	// oxlint-disable-next-line no-restricted-properties -- Recovery cooldown uses wall-clock time outside React.
	const recentlyRecovered = Date.now() - lastRefreshRecoveryAt < REFRESH_RECOVERY_COOLDOWN_MS;
	if (recentlyRecovered) return;
	const result = await refreshAccessToken();
	if (result === "unavailable") return;
	if (result === "refreshed") {
		// oxlint-disable-next-line no-restricted-properties -- Recovery cooldown uses wall-clock time outside React.
		lastRefreshRecoveryAt = Date.now();
		// Refetch queries, but never replay mutations after an ambiguous failure.
		void queryClient.invalidateQueries();
		return;
	}
	redirectToLogin(currentPath);
}

export function redirectToLogin(
	currentPath = window.location.pathname + window.location.search,
): void {
	const target = new URL("/login", window.location.origin);
	target.searchParams.set("returnTo", safeReturnTo(currentPath));
	window.location.assign(target.toString());
}

export function __resetSessionRecoveryForTests(): void {
	recovery = null;
	lastRefreshRecoveryAt = 0;
}
