import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";

import { getCurrentUserOptions, getCurrentUserQueryKey } from "@/api/@tanstack/react-query.gen";

import { refreshAccessToken } from "./session-refresh";

/**
 * Proactive, activity-gated session keep-alive (ADR 0017).
 *
 * Hephaestus uses a short-lived HttpOnly access cookie (default 15 min) and a rotating
 * {@code POST /auth/refresh} (new jti, old revoked) — the browser never holds the token (the BFF
 * pattern recommended by the IETF "OAuth 2.0 for Browser-Based Apps" BCP). Nothing was renewing it, so
 * an active user was hard-logged-out every ~15 min.
 *
 * This renews the cookie BEFORE it expires, but only while the user is genuinely active — satisfying
 * BOTH OWASP session requirements at once: active users are never interrupted, while an idle session
 * still times out (no silent forever-session). Expiry is enforced server-side; the client only schedules
 * the renewal, reading the authoritative {@code accessTokenExpiresAt} the server returns on {@code /user}.
 *
 * @see https://datatracker.ietf.org/doc/html/draft-ietf-oauth-browser-based-apps
 * @see https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
 */

/**
 * Renew this long before expiry so a request never races a just-lapsed token. It is also the window
 * in which the server ends an impersonation instead of rotating it (`IMPERSONATION_EXIT_SKEW` in
 * `AuthSessionService`): shortening this one without shortening that one leaves an impersonation
 * whose last rotation mints a token that is already expired.
 */
const REFRESH_SKEW_MS = 60_000;
/** Coalesce activity bookkeeping so the listeners are effectively free on a busy page. */
const ACTIVITY_THROTTLE_MS = 10_000;
const ACTIVITY_EVENTS = ["pointerdown", "keydown", "scroll", "pointermove", "wheel"] as const;

/**
 * Schedules the renewal while authenticated. Mount once inside the authenticated app (see
 * {@link SessionKeepAlive}); reads the same {@code getCurrentUser} cache the auth context uses, so it
 * adds no extra request.
 */
export function useSessionKeepAlive() {
	const queryClient = useQueryClient();
	const { data: user } = useQuery({ ...getCurrentUserOptions(), retry: false, staleTime: 30_000 });
	const expiresAtSec = user?.accessTokenExpiresAt ?? undefined;
	const isAuthenticated = Boolean(user);

	// Did the user interact during the CURRENT token's life? Seeded true so a freshly-loaded session
	// renews once (we never drop a just-loaded session); every later cycle requires a fresh interaction,
	// otherwise the token is left to lapse = OWASP idle timeout.
	const activeThisCycleRef = useRef(true);
	const firstCycleRef = useRef(true);

	useEffect(() => {
		if (!isAuthenticated || !expiresAtSec) {
			return;
		}
		// A new token (effect re-runs when accessTokenExpiresAt changes) starts a fresh cycle: keep cycle 1
		// "active" (mount), but require a new interaction for every subsequent cycle.
		if (firstCycleRef.current) {
			firstCycleRef.current = false;
		} else {
			activeThisCycleRef.current = false;
		}

		let lastMark = 0;
		const markActive = () => {
			// oxlint-disable-next-line no-restricted-properties -- Runs on every `pointermove`, where the point is not to wake React at all; `useNow` would both re-render and tick coarser than ACTIVITY_THROTTLE_MS.
			const now = Date.now();
			if (now - lastMark >= ACTIVITY_THROTTLE_MS) {
				lastMark = now;
				activeThisCycleRef.current = true;
			}
		};
		for (const ev of ACTIVITY_EVENTS) {
			window.addEventListener(ev, markActive, { passive: true });
		}

		const renew = async () => {
			// Either way we refetch /user: on success to pick up the new expiry (reschedules this effect);
			// on failure the identity query 401s → useAuth() flips to logged-out and guards redirect.
			await refreshAccessToken();
			await queryClient.invalidateQueries({ queryKey: getCurrentUserQueryKey() });
		};

		// Proactive renewal — only if the user was active during this token's life.
		const expiresAtMs = expiresAtSec * 1000;
		// oxlint-disable-next-line no-restricted-properties -- A one-shot `setTimeout` delay. A ticking clock would be an effect dependency, re-arming this timer every tick so it never reaches a due date minutes out.
		const dueInMs = Math.max(0, expiresAtMs - REFRESH_SKEW_MS - Date.now());
		const timer = window.setTimeout(() => {
			if (activeThisCycleRef.current) {
				void renew();
			}
			// Idle this whole cycle → do nothing; the cookie lapses and the next request logs them out.
		}, dueInMs);

		// Returning to a backgrounded tab (where setTimeout is throttled): coming back IS activity, so
		// renew immediately if the token is at/near expiry — the user's first action isn't bounced.
		const onWake = () => {
			if (document.visibilityState !== "visible") {
				return;
			}
			activeThisCycleRef.current = true;
			// oxlint-disable-next-line no-restricted-properties -- Runs on wake from a backgrounded tab, where `useNow`'s own `setInterval` was throttled with every other timer, so its reading is however stale the tab was away.
			if (Date.now() >= expiresAtMs - REFRESH_SKEW_MS) {
				void renew();
			}
		};
		document.addEventListener("visibilitychange", onWake);
		window.addEventListener("focus", onWake);

		return () => {
			window.clearTimeout(timer);
			for (const ev of ACTIVITY_EVENTS) {
				window.removeEventListener(ev, markActive);
			}
			document.removeEventListener("visibilitychange", onWake);
			window.removeEventListener("focus", onWake);
		};
	}, [isAuthenticated, expiresAtSec, queryClient]);
}

/** Headless mount point for {@link useSessionKeepAlive}. Render once inside the authenticated app. */
export function SessionKeepAlive() {
	useSessionKeepAlive();
	return null;
}
