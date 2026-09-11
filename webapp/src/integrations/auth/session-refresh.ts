import { refresh } from "@/api/sdk.gen";

import { withSessionLock } from "./session-lock";

export type SessionRefreshResult = "refreshed" | "expired" | "unavailable";

let inFlight: Promise<SessionRefreshResult> | null = null;

async function rotate(): Promise<SessionRefreshResult> {
	const { response } = await refresh();
	if (!response) return "unavailable";
	if (response.ok) return "refreshed";
	return response.status === 401 ? "expired" : "unavailable";
}

/** Share overlapping renewals within this tab; the session lock coordinates other tabs. */
export function refreshAccessToken(): Promise<SessionRefreshResult> {
	inFlight ??= withSessionLock(rotate)
		.catch((): SessionRefreshResult => "unavailable")
		.finally(() => {
			inFlight = null;
		});
	return inFlight;
}
