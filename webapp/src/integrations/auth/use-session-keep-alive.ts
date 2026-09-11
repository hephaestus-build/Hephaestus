/* oxlint-disable no-restricted-properties -- Session scheduling needs wall-clock time, not render time. */
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";

import { getCurrentUserQueryKey } from "@/api/@tanstack/react-query.gen";

import { currentUserQueryOptions } from "./guard";
import { redirectToLogin } from "./session-expiry";
import { refreshAccessToken } from "./session-refresh";

// Must not exceed AuthSessionService.IMPERSONATION_EXIT_SKEW: renewal also triggers operator restoration.
const REFRESH_SKEW_MS = 60_000;
const MAX_RENEWAL_DELAY_MS = 60 * 60_000;
const ACTIVITY_THROTTLE_MS = 10_000;
const ACTIVITY_EVENTS = ["pointerdown", "keydown", "scroll", "pointermove", "wheel"] as const;

export function useSessionKeepAlive() {
	const queryClient = useQueryClient();
	const { data: user } = useQuery(currentUserQueryOptions());
	const expiresAtSec = user?.accessTokenExpiresAt ?? undefined;
	const isAuthenticated = Boolean(user);

	const activeThisCycleRef = useRef(true);
	const previousExpiryRef = useRef<number | undefined>(undefined);

	useEffect(() => {
		if (!isAuthenticated || !expiresAtSec) {
			return;
		}
		// Preserve activity through StrictMode effect replays.
		if (previousExpiryRef.current !== undefined && previousExpiryRef.current !== expiresAtSec) {
			activeThisCycleRef.current = false;
		}
		previousExpiryRef.current = expiresAtSec;
		let renewAtMs = Math.min(
			expiresAtSec * 1000 - REFRESH_SKEW_MS,
			Date.now() + MAX_RENEWAL_DELAY_MS,
		);
		let disposed = false;
		let timer: number;
		const schedule = () => {
			window.clearTimeout(timer);
			timer = window.setTimeout(
				() => {
					if (activeThisCycleRef.current && document.visibilityState === "visible") {
						void renew();
					}
				},
				Math.max(0, renewAtMs - Date.now()),
			);
		};
		const renew = async () => {
			const result = await refreshAccessToken();
			if (disposed || result === "unavailable") return;
			if (result === "expired") {
				redirectToLogin();
				return;
			}
			// Rotation can succeed without extending exp at the absolute session ceiling.
			activeThisCycleRef.current = false;
			renewAtMs = Date.now() + MAX_RENEWAL_DELAY_MS;
			schedule();
			await queryClient.invalidateQueries({ queryKey: getCurrentUserQueryKey() });
			// oxlint-disable-next-line typescript/no-unnecessary-condition -- Cleanup can run while identity revalidation is pending.
			if (disposed) return;
			const identity = queryClient.getQueryState(currentUserQueryOptions().queryKey);
			if (identity?.status === "success" && identity.data?.accessTokenExpiresAt) {
				renewAtMs = Math.min(renewAtMs, identity.data.accessTokenExpiresAt * 1000);
				schedule();
			}
		};

		let lastMark = 0;
		const markActive = () => {
			const now = Date.now();
			if (now - lastMark >= ACTIVITY_THROTTLE_MS) {
				lastMark = now;
				activeThisCycleRef.current = true;
				// The scheduled check may already have skipped this idle cycle.
				if (now >= renewAtMs) void renew();
			}
		};
		for (const ev of ACTIVITY_EVENTS) {
			window.addEventListener(ev, markActive, { passive: true });
		}

		schedule();

		const onWake = () => {
			if (document.visibilityState !== "visible") {
				return;
			}
			activeThisCycleRef.current = true;
			if (Date.now() >= renewAtMs) {
				void renew();
			}
		};
		document.addEventListener("visibilitychange", onWake);
		window.addEventListener("focus", onWake);

		return () => {
			disposed = true;
			window.clearTimeout(timer);
			for (const ev of ACTIVITY_EVENTS) {
				window.removeEventListener(ev, markActive);
			}
			document.removeEventListener("visibilitychange", onWake);
			window.removeEventListener("focus", onWake);
		};
	}, [isAuthenticated, expiresAtSec, queryClient]);
}

export function SessionKeepAlive() {
	useSessionKeepAlive();
	return null;
}
