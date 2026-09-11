import type { AgentSession } from "@earendil-works/pi-coding-agent";

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
