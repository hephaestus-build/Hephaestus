import { type AgentSession, estimateTokens } from "@earendil-works/pi-coding-agent";

import { errorText } from "./pi-error-text.ts";

function textTokens(text: string): number {
	return estimateTokens({ role: "user", content: text, timestamp: 0 });
}

function requestTokens(session: AgentSession): number {
	return textTokens(session.systemPrompt) + textTokens(JSON.stringify(session.agent.state.tools));
}

/** Usage can be unknown after compaction; the canonical projection excludes summarized history. */
function heldTokens(session: AgentSession): number {
	const { messages } = session.sessionManager.buildSessionProjection();
	const history = messages
		.filter((message) => message.role !== "system")
		.reduce((total, message) => total + estimateTokens(message), 0);
	// The active request replaces earlier system/tool revisions. Fresh sessions have no usage yet.
	return Math.max(session.getContextUsage()?.tokens ?? 0, history + requestTokens(session));
}

/** Reserve the complete incoming turn and response. Rebuild once after native compaction. */
export async function prepareTurnText(
	session: AgentSession,
	buildText: () => string,
): Promise<string | null> {
	const { model } = session;
	if (!model) {
		throw new Error("No model is selected for the review turn");
	}
	const reserve = Math.max(
		model.maxTokens,
		session.settingsManager.getCompactionSettings(model).reserveTokens,
	);
	const fits = (text: string) =>
		heldTokens(session) + textTokens(text) + reserve <= model.contextWindow;
	let text = buildText();
	if (requestTokens(session) + textTokens(text) + reserve > model.contextWindow) {
		return null;
	}
	if (fits(text)) {
		return text;
	}
	let aborted = false;
	const wasAborted = () => aborted;
	const unsubscribe = session.subscribe((event) => {
		if (event.type === "compaction_end" && event.aborted) {
			aborted = true;
		}
	});
	try {
		await session.compact();
	} catch (error) {
		if (wasAborted()) {
			return null;
		}
		// Native prompt overflow recovery remains available when the incoming input itself fits.
		console.error(`[pi-runner] preparatory compaction failed: ${errorText(error)}`);
		return buildText();
	} finally {
		unsubscribe();
	}
	text = buildText();
	return fits(text) ? text : null;
}
