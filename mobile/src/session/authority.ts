import { useSyncExternalStore } from "react";

import { client } from "@/api/client.gen";
import { clearDrafts } from "@/heph/drafts";
import { clearSharing } from "@/heph/sharing";
import { USER_AGENT } from "@/instance/instance";
import { replaceQueryClient } from "@/query-client";
import { clearPendingReport } from "@/report/report";
import { resetWorkspace } from "@/workspace/workspace-store";

import { wireApiClient } from "./api-client";
import type { SessionState } from "./session-core";
import { session } from "./session-store";

/** Identifies one session: every sign-in and sign-out starts a new one. */
function sessionKey(state: SessionState): string {
	return state.status === "signedIn" ? `in:${state.epoch}` : state.status;
}

let current = sessionKey(session.getState());
resetWorkspace(current, false);

wireApiClient(client, session, USER_AGENT);

// Everything scoped to an account changes hands here, synchronously as the session changes and before
// any screen renders for the next one — never in a React effect that a batched update could skip.
session.subscribe(() => {
	const state = session.getState();
	const next = sessionKey(state);
	if (next === current) {
		return;
	}
	current = next;
	replaceQueryClient();
	clearDrafts();
	clearSharing();
	clearPendingReport();
	resetWorkspace(next, state.status === "signedOut");
});

/** The current session's key: the whole signed-in tree is keyed by it. */
export function useSessionKey(): string {
	return useSyncExternalStore(
		(listener) => session.subscribe(listener),
		() => sessionKey(session.getState()),
	);
}

export function currentSessionKey(): string {
	return current;
}
