import type { AgentSession } from "@earendil-works/pi-coding-agent";

/** The custom entry type that binds a native session file to the review it belongs to. */
export const REVIEW_SESSION_ENTRY = "hephaestus.review-session";

export type SessionPhase = "practice" | "public-review" | "private-feedback";

/** What a session reviewed, as the runner knows it; null where the task did not say. */
export interface ReviewSessionBinding {
	phase: SessionPhase;
	practiceSlug: string | null;
	practiceRevisionId: number | null;
	model: string;
	jobId: string | null;
	workspaceId: number | null;
	openedAt: string;
}

/** Preserve Pi's final messages and settlement events before disconnecting its persistence listener. */
export async function stopSession(session: AgentSession) {
	// Aborting must not start a queued continuation.
	session.clearQueue();
	try {
		await session.abort();
	} finally {
		session.dispose();
	}
}
