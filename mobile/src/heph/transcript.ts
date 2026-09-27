import type { UIMessage } from "ai";
import { z } from "zod";

import type { HephAccess } from "./use-heph-access";

/** What the mentor stores on a message: the model and usage today, `error` when a turn stopped early. */
export interface HephMetadata {
	model?: string;
	error?: string;
	/** Where the server's own record of the turn stands; absent on a message streamed here. */
	status?: "in_flight" | "completed" | "interrupted";
	/**
	 * How the model's turn ended, as the AI SDK names it (`stop`, `length`, `content-filter`,
	 * `tool-calls`, `error`, `other`). A turn can be `completed` and still have ended in `error`.
	 */
	finishReason?: string;
}

export type HephMessage = UIMessage<HephMetadata>;

/**
 * A stored transcript is read from the server as untyped JSON. Unknown part kinds and keys survive,
 * because the renderer narrows on `type`; a text part must carry its text, because the renderer
 * reads it unguarded.
 */
const partSchema = z
	.looseObject({ type: z.string() })
	.refine((value) => value.type !== "text" || typeof value.text === "string", {
		message: "A text part must carry its text",
	});

const message = z.looseObject({
	id: z.uuid(),
	role: z.enum(["system", "user", "assistant"]),
	parts: z.array(partSchema),
	metadata: z
		.looseObject({
			model: z.string().optional(),
			error: z.string().optional(),
			status: z.enum(["in_flight", "completed", "interrupted"]).optional(),
			// A reason this app does not know yet must not make the whole conversation unreadable.
			finishReason: z.string().optional(),
		})
		.optional(),
});

const transcript = z.array(message);

function isTranscript(value: unknown): value is HephMessage[] {
	return transcript.safeParse(value).success;
}

export function parseTranscript(messages: unknown): HephMessage[] | undefined {
	return isTranscript(messages) ? messages : undefined;
}

/** The readable text of a message, its text parts in order. */
export function textOf(item: HephMessage): string {
	return item.parts
		.map((piece) => (piece.type === "text" ? piece.text : ""))
		.filter((text) => text !== "")
		.join("\n\n");
}

/**
 * Whether the last reply stopped before it finished: the server records the reason on the message when
 * a turn is cut off — the app went to the background, the connection dropped. Saying so is the honest
 * alternative to presenting half an answer as a whole one.
 */
export function lastReplyInterrupted(messages: HephMessage[]): boolean {
	const last = messages.at(-1);
	return last?.role === "assistant" && typeof last.metadata?.error === "string";
}

export type TranscriptState = "loading" | "failed" | "unreadable" | "ready";

/**
 * What a conversation screen can show and accept. A new conversation is ready at once. An existing
 * one is ready only with its history: while nothing is on screen, it is loading, failed to load, or
 * unreadable — the server answered with a history this app cannot read. None of those is an empty
 * conversation, and none accepts a message, which would reach Heph without the context the person
 * sees missing. History already on screen stays, and stays usable, whatever a later read returns.
 */
export function transcriptState({
	isNew,
	shown,
	fetch,
	readable,
}: {
	isNew: boolean;
	/** Messages on screen, from a readable history or sent here. */
	shown: number;
	fetch: "pending" | "error" | "success";
	/** Whether the latest history the server returned could be read. */
	readable: boolean;
}): TranscriptState {
	if (isNew || shown > 0) {
		return "ready";
	}
	if (fetch === "pending") {
		return "loading";
	}
	if (fetch === "error") {
		return "failed";
	}
	return readable ? "ready" : "unreadable";
}

/**
 * Whether the composer takes a message, and what its empty field says. Only a conversation that is
 * ready, with Heph available, takes one; otherwise the field names the reason, and a conversation that
 * is still loading never claims Heph is unavailable.
 */
export function composerState(
	access: HephAccess,
	view: TranscriptState,
): { enabled: boolean; placeholder: string } {
	if (access === "workspace-off" || access === "no-access") {
		return { enabled: false, placeholder: "Heph is not available" };
	}
	if (access === "unreachable") {
		return { enabled: false, placeholder: "Could not reach Heph" };
	}
	if (access === "checking" || view === "loading") {
		return { enabled: false, placeholder: "Loading…" };
	}
	if (view !== "ready") {
		return { enabled: false, placeholder: "Nothing can be sent until it loads" };
	}
	return { enabled: true, placeholder: "Message Heph" };
}

/**
 * The question to send again after a reply that did not finish: the text of the person's last message.
 * Sending it again is a new turn — the server takes each message once, so the old one cannot be
 * resent — and the conversation keeps both, as they happened. Undefined when there is nothing to send:
 * no message from the person, or one with no text.
 */
export function questionToResend(messages: readonly HephMessage[]): string | undefined {
	for (let index = messages.length - 1; index >= 0; index -= 1) {
		const candidate = messages[index];
		if (candidate?.role === "user") {
			const text = textOf(candidate).trim();
			return text === "" ? undefined : text;
		}
	}
	return undefined;
}

/** How the last reply ended in this screen, as the chat reported it; undefined before any has ended. */
export interface ReplyEnding {
	/** The person stopped it. */
	aborted: boolean;
	finishReason?: string;
}

/** Endings a model reports for a reply it did not finish. */
const INCOMPLETE = new Set(["error", "length", "content-filter"]);

/** Why the last reply is incomplete: stopped, failed, or ended early (too long, or filtered). */
export type Interruption = "stopped" | "failed" | "cut-off";

/**
 * Why the last reply is incomplete, when it is. A turn has two records: how it ended here, reported
 * the moment it ends, and the server's, which may arrive later — the copy read back straight after a
 * stop can still be in flight. The server's record wins where it says something; otherwise what
 * happened here stands.
 *
 * - The server cut the turn off (the app went to the background, the connection dropped): stopped.
 * - The person stopped it here: stopped, unless the server holds the reply as properly finished.
 * - The model's turn ended in an error — which the server still stores as `completed`: failed. The
 *   provider's error text is never shown.
 * - It ran out of length, or the AI service's filter ended it: ended early, possibly incomplete.
 */
export function interruptionOf({
	busy,
	failed,
	ended,
	messages,
}: {
	busy: boolean;
	/** The request itself failed. */
	failed: boolean;
	ended: ReplyEnding | undefined;
	messages: readonly HephMessage[];
}): Interruption | undefined {
	if (busy) {
		return undefined;
	}
	if (failed) {
		return "failed";
	}
	const last = messages.at(-1);
	const record = last?.role === "assistant" ? last.metadata : undefined;
	if (typeof record?.error === "string" || record?.status === "interrupted") {
		return "stopped";
	}
	const reason = record?.finishReason ?? ended?.finishReason;
	const finished = record?.status === "completed" && !INCOMPLETE.has(reason ?? "");
	if (ended?.aborted === true && !finished) {
		return "stopped";
	}
	if (reason === "error") {
		return "failed";
	}
	if (reason === "length" || reason === "content-filter") {
		return "cut-off";
	}
	return undefined;
}

/**
 * What the server's record says about how an earlier reply ended, for a reply no longer the last one.
 * Only the record speaks here: how it ended in some earlier screen is gone, and nothing is guessed.
 * The same reasons as for the last reply, but no one is said to have stopped it.
 */
export function recordedInterruption(metadata: HephMetadata | undefined): Interruption | undefined {
	if (typeof metadata?.error === "string" || metadata?.status === "interrupted") {
		return "stopped";
	}
	if (metadata?.finishReason === "error") {
		return "failed";
	}
	if (metadata?.finishReason === "length" || metadata?.finishReason === "content-filter") {
		return "cut-off";
	}
	return undefined;
}
