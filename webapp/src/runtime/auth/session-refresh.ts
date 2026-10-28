import { z } from "zod";

import { refresh } from "@/api/sdk.gen";

import { withSessionLock } from "./session-lock";

export type SessionRefreshResult = "refreshed" | "expired" | "unavailable";

/** Late refusals after a real renewal do not need another renewal. */
export const SESSION_COOLDOWN_MS = 10_000;

const REFRESHED_KEY = "hephaestus:session-refreshed";
const refreshStamp = z.object({ id: z.uuid(), completedAt: z.number().nonnegative() });
let withoutStorage: z.infer<typeof refreshStamp> | undefined;

function lastRefresh() {
	try {
		const stored = window.localStorage.getItem(REFRESHED_KEY);
		return stored === null ? undefined : refreshStamp.safeParse(JSON.parse(stored)).data;
	} catch {
		return withoutStorage;
	}
}

function recordRefresh(): void {
	// oxlint-disable-next-line no-restricted-properties -- Tabs compare a renewal in wall-clock time.
	const stamp = { id: crypto.randomUUID(), completedAt: Date.now() };
	withoutStorage = stamp;
	try {
		window.localStorage.setItem(REFRESHED_KEY, JSON.stringify(stamp));
	} catch {
		// Web Locks still serialize cookie changes when storage is unavailable.
	}
}

export function sessionRefreshedRecently(): boolean {
	const stamp = lastRefresh();
	// oxlint-disable-next-line no-restricted-properties -- Tabs compare a renewal in wall-clock time.
	return stamp !== undefined && Date.now() - stamp.completedAt < SESSION_COOLDOWN_MS;
}

let inFlight: Promise<SessionRefreshResult> | null = null;

async function rotate(): Promise<SessionRefreshResult> {
	const { response } = await refresh();
	if (!response) {
		return "unavailable";
	}
	if (response.ok) {
		recordRefresh();
		return "refreshed";
	}
	return response.status === 401 ? "expired" : "unavailable";
}

/** Calls queued behind the same renewal share its result; a later caller can renew again. */
export async function refreshAccessToken(): Promise<SessionRefreshResult> {
	const observed = lastRefresh()?.id;
	inFlight ??= withSessionLock(async (): Promise<SessionRefreshResult> => {
		const completed = lastRefresh();
		return completed !== undefined && completed.id !== observed ? "refreshed" : rotate();
	})
		.catch((): SessionRefreshResult => "unavailable")
		.finally(() => {
			inFlight = null;
		});
	return inFlight;
}
