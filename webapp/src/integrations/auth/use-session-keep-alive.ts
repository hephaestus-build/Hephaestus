/* oxlint-disable no-restricted-properties -- Session scheduling needs wall-clock time, not render time. */
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";

import { getCurrentUserQueryKey } from "@/api/@tanstack/react-query.gen";

import { currentUserQueryOptions } from "./guard";
import { refreshAccessToken } from "./session-refresh";

// Must match AuthSessionService.IMPERSONATION_EXIT_SKEW so the last rotation cannot mint an expired token.
const REFRESH_SKEW_MS = 60_000;
const ACTIVITY_THROTTLE_MS = 10_000;
const ACTIVITY_EVENTS = ["pointerdown", "keydown", "scroll", "pointermove", "wheel"] as const;

/** Renew active sessions before cookie expiry; the server enforces idle expiry (ADR 0017). */
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
		// A mount counts as activity; only a new token resets activity, not an effect replay.
		if (previousExpiryRef.current !== undefined && previousExpiryRef.current !== expiresAtSec) {
			activeThisCycleRef.current = false;
		}
		previousExpiryRef.current = expiresAtSec;
		const renewAtMs = expiresAtSec * 1000 - REFRESH_SKEW_MS;
		const renew = async () => {
			await refreshAccessToken();
			await queryClient.invalidateQueries({ queryKey: getCurrentUserQueryKey() });
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

		const dueInMs = Math.max(0, renewAtMs - Date.now());
		const timer = window.setTimeout(() => {
			if (activeThisCycleRef.current) {
				void renew();
			}
		}, dueInMs);

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
