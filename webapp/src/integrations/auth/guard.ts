import type { QueryClient } from "@tanstack/react-query";

import {
	getConsentStatusOptions,
	getCurrentUserMembershipOptions,
	getCurrentUserOptions,
} from "@/api/@tanstack/react-query.gen";
import type { CurrentUserView, WorkspaceMembership } from "@/api/types.gen";
import { QUERY_STALE_TIME_MS } from "@/integrations/tanstack-query/query-defaults";
import { isRecord } from "@/lib/is-record";

/**
 * Shared by the route guards and `AuthContext` so both read one cache entry on one schedule. A 401
 * is a definitive "not signed in", not a transient error, so it is never retried.
 */
export function currentUserQueryOptions() {
	return { ...getCurrentUserOptions(), retry: false, staleTime: QUERY_STALE_TIME_MS };
}

/**
 * Resolve the current user for route `beforeLoad` guards (ADR 0017 cookie-session auth).
 *
 * Reads `GET /user` through the query client so the result is cached and shared
 * with the in-app `useAuth` surface — this makes the first paint correct (no auth
 * flash) because the route blocks on the same query the rest of the app reads.
 *
 * Stale-while-revalidate in two calls: `staleTime: "static"` answers from cache and blocks only
 * when nothing is cached, then the unawaited second call refetches under the real `staleTime`.
 * This guard runs on EVERY authenticated navigation, so awaiting the refresh would let one flaky
 * `GET /user` bounce a signed-in user to /login, while never refreshing would leave a role change
 * unseen for the life of the tab.
 *
 * A 401/403 (or any fetch failure) resolves to `null` rather than throwing, so
 * guards can branch on authentication without try/catch noise at every call site.
 */
export async function resolveCurrentUser(
	queryClient: QueryClient,
): Promise<CurrentUserView | null> {
	const options = currentUserQueryOptions();
	try {
		const user = await queryClient.query({ ...options, staleTime: "static" });
		void queryClient.query(options).catch(() => undefined);
		return user;
	} catch {
		return null;
	}
}

/**
 * Whether an account is an instance admin. The authoritative source is the `appRole` field returned
 * by `GET /user` (ADR 0017); the client is not a security boundary (every admin endpoint is enforced
 * server-side by `hasAuthority('app_admin')`), so a single appRole check is both correct and simplest.
 * Accepts the raw generated `CurrentUserView` (or null) so route guards and `useAuth()` share one
 * definition.
 */
export function isAppAdmin(
	user: Partial<Pick<CurrentUserView, "appRole">> | null | undefined,
): boolean {
	return user?.appRole === "APP_ADMIN";
}

/** A "not a member" answer, as opposed to a transport failure, which carries no status at all. */
function isServerRefusal(error: unknown): boolean {
	if (!isRecord(error)) return false;
	const status = error.status;
	return typeof status === "number" && status >= 400 && status < 500;
}

/**
 * Shared by route guards and hooks, including callers with their own QueryClient. The freshness
 * window bounds how long a role change takes to reach the UI.
 */
export function workspaceMembershipQueryOptions(workspaceSlug: string) {
	return {
		...getCurrentUserMembershipOptions({ path: { workspaceSlug } }),
		staleTime: QUERY_STALE_TIME_MS,
		// Retrying a refusal would stall the redirect of everyone who is legitimately not a member.
		retry: (failureCount: number, error: unknown) => !isServerRefusal(error) && failureCount < 2,
	};
}

/**
 * Blocking on purpose, unlike `resolveCurrentUser` above: `query()` honours `staleTime`, so an
 * admin navigation more than 30s after the last check re-verifies the role rather than trusting a
 * cached one for the life of the tab. Any failure resolves to `null` — unverifiable is not allowed.
 */
export async function resolveWorkspaceMembership(
	queryClient: QueryClient,
	workspaceSlug: string,
): Promise<WorkspaceMembership | null> {
	try {
		return await queryClient.query(workspaceMembershipQueryOptions(workspaceSlug));
	} catch {
		return null;
	}
}

/**
 * Fully percent-decode a value, bounded against malicious double/triple-encoding.
 *
 * A single `decodeURIComponent` leaves `%252f` as `%2f`, so a payload like `/%252f%252fevil`
 * would pass a one-shot decode-then-check yet decode again in a downstream parser. We decode
 * in a bounded loop until the value is stable (or the cap is hit), so the checks below run on
 * the value a browser/router would ultimately see. The cap also stops a decode bomb.
 */
function fullyDecode(value: string): string {
	let current = value;
	for (let i = 0; i < 5; i++) {
		let decoded: string;
		try {
			decoded = decodeURIComponent(current);
		} catch {
			// Malformed percent-encoding (e.g. a lone "%") — treat as already fully decoded.
			return current;
		}
		if (decoded === current) {
			return current;
		}
		current = decoded;
	}
	return current;
}

/**
 * Validate a `returnTo` redirect target. Accepts only same-origin absolute paths
 * (a single leading slash, no `//` protocol-relative escape, no scheme), otherwise
 * falls back to `/`. Prevents open-redirect via crafted `?returnTo=` params.
 *
 * Checks run on the FULLY DECODED value (decode-then-check), so percent-encoded escapes —
 * `/%2f%2fevil.com`, `/%09/evil`, `/%20/evil`, `/%5cevil`, `/@evil` — cannot smuggle a
 * protocol-relative URL, control char, whitespace, or backslash past the same-origin gate.
 */
export function safeReturnTo(value: string | undefined): string {
	if (!value) return "/";
	const decoded = fullyDecode(value);
	// Whitespace (incl. space/tab) and control chars (NUL, newline, DEL, …) can defeat downstream
	// parsers or hide an escape — reject outright on the decoded value.
	if (/[\s\p{Cc}]/u.test(decoded)) return "/";
	// Must be a rooted path and not a protocol-relative `//host` escape.
	if (!decoded.startsWith("/") || decoded.startsWith("//")) return "/";
	// Reject a leading-segment that smuggles a scheme ("/javascript:"), a backslash escape
	// ("/\evil", which browsers normalise to "//evil"), or a userinfo `@` host trick ("/@evil").
	if (/^\/[\\]/.test(decoded) || /^\/+[a-z]+:/i.test(decoded) || decoded.startsWith("/@"))
		return "/";
	// Validation ran on the fully-decoded value; return the ORIGINAL so legitimate encoded
	// segments (e.g. a `%26` in a query string) are preserved exactly as the caller intended.
	return value;
}

/**
 * Whether the reader still owes the transparency notice.
 *
 * The server answers every gated call with 428 until the choice is recorded, so a route that runs a
 * gated query first would throw before anything could ask for the choice. Routes ask this instead
 * and skip the work; the notice is then put to the reader in place, over the page they were opening.
 */
export async function consentIsPending(queryClient: QueryClient): Promise<boolean> {
	// Only a signed-in reader can owe it. Asking on a public page would spend a request that can only
	// answer 401, on the landing page's critical path, for every anonymous visitor.
	if (!(await resolveCurrentUser(queryClient))) return false;
	try {
		const status = await queryClient.query(getConsentStatusOptions({}));
		return !status.completed;
	} catch {
		// Only an explicit "not answered" blocks the application. Treating an unanswerable question as
		// outstanding would put an undismissable notice in front of every reader whenever this one
		// call failed, with no way out but a reload — and it would not even be protective, because the
		// server refuses the gated calls itself. A reader who does owe the notice still meets that
		// refusal; a reader who does not keeps working.
		return false;
	}
}
