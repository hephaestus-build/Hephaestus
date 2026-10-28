import { refresh } from "@/api/sdk.gen";

import { withSessionLock } from "./session-lock";

export type SessionRefreshResult = "refreshed" | "expired" | "unavailable";

/** A 401 this soon after a renewal or a confirmed identity answers a request that left before it. */
export const SESSION_COOLDOWN_MS = 10_000;

// Tabs share the cookie, so they share the cooldown through storage.
const CONFIRMED_AT_KEY = "hephaestus:session-confirmed-at";
let confirmedAtWithoutStorage = 0;

/** Storage can be unavailable or full. The cooldown then covers this tab only. */
export function confirmSession(): void {
	// oxlint-disable-next-line no-restricted-properties -- Tabs compare the cooldown in wall-clock time.
	const now = Date.now();
	try {
		window.localStorage.setItem(CONFIRMED_AT_KEY, String(now));
	} catch {
		confirmedAtWithoutStorage = now;
	}
}

export function sessionConfirmedRecently(): boolean {
	let confirmedAt = confirmedAtWithoutStorage;
	try {
		confirmedAt = Math.max(confirmedAt, Number(window.localStorage.getItem(CONFIRMED_AT_KEY)) || 0);
	} catch {
		// This tab still keeps its own cooldown.
	}
	// oxlint-disable-next-line no-restricted-properties -- Tabs compare the cooldown in wall-clock time.
	return Date.now() - confirmedAt < SESSION_COOLDOWN_MS;
}

let inFlight: Promise<SessionRefreshResult> | null = null;

async function rotate(): Promise<SessionRefreshResult> {
	// Recheck after taking the session lock: another tab may have renewed while this one waited.
	if (sessionConfirmedRecently()) {
		return "refreshed";
	}
	const { response } = await refresh();
	if (!response) {
		return "unavailable";
	}
	if (response.ok) {
		confirmSession();
		return "refreshed";
	}
	return response.status === 401 ? "expired" : "unavailable";
}

/** Share overlapping renewals within this tab; the session lock coordinates other tabs. */
export async function refreshAccessToken(): Promise<SessionRefreshResult> {
	inFlight ??= withSessionLock(rotate)
		.catch((): SessionRefreshResult => "unavailable")
		.finally(() => {
			inFlight = null;
		});
	return inFlight;
}
