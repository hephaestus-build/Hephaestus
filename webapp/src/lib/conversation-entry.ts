import { z } from "zod";

import { isRecord } from "@/lib/is-record";

import type {
	Conversation,
	ConversationEntry,
	ConversationRead,
	ConversationTurn,
	ConversationTurnRequest,
} from "@/api/types.gen";
import {
	zConversation,
	zConversationEntry,
	zConversationRead,
	zConversationTurn,
	zConversationTurnRequest,
} from "@/api/zod.gen";

export type ConversationLiveEvent =
	| { type: "snapshot"; conversation: Conversation }
	| { type: "turn"; turn: ConversationTurn }
	| { type: "entry"; entry: ConversationEntry }
	| { type: "read"; read: ConversationRead };

function parseJson(data: string): unknown {
	try {
		return JSON.parse(data);
	} catch {
		return undefined;
	}
}

/**
 * One named event of `GET /conversation/events`, or `undefined` when its payload does not match.
 * An `EventSource` bypasses the SDK, so the generated schemas check each payload.
 */
export function parseLiveEvent(type: string, data: string): ConversationLiveEvent | undefined {
	const decoded = parseJson(data);
	switch (type) {
		case "snapshot": {
			const parsed = zConversation.safeParse(decoded);
			return parsed.success ? { type, conversation: parsed.data } : undefined;
		}
		case "turn": {
			const parsed = zConversationTurn.safeParse(decoded);
			return parsed.success ? { type, turn: parsed.data } : undefined;
		}
		case "entry": {
			const parsed = zConversationEntry.safeParse(decoded);
			return parsed.success ? { type, entry: parsed.data } : undefined;
		}
		case "read": {
			const parsed = zConversationRead.safeParse(decoded);
			return parsed.success ? { type, read: parsed.data } : undefined;
		}
		default: {
			return undefined;
		}
	}
}

/** The mutation cache types every mutation's variables as `unknown`, so a pending message is read back here. */
export function turnRequestOf(variables: unknown): ConversationTurnRequest | undefined {
	const parsed = z.object({ body: zConversationTurnRequest }).safeParse(variables);
	return parsed.success ? parsed.data.body : undefined;
}

/**
 * A message the developer sent that the server has not stored yet. `failed` keeps the words on
 * screen with the reason, so a retry sends them again under the same `requestId` and never asks
 * twice.
 */
export type PendingMessage = {
	requestId: string;
	text: string;
	parentEntryId?: string;
	createdAt: Date;
} & ({ status: "sending" } | { status: "failed"; reason: string });

/** Hephaestus's own deleted messages leave the conversation; the developer's stay as a placeholder. */
export function isRemovedEntry(entry: Pick<ConversationEntry, "kind" | "deleted">): boolean {
	return entry.deleted && entry.kind === "ANSWER";
}

/** The time a pending read request asks the server to mark, read back from the mutation cache. */
export function readAtOf(variables: unknown): Date | undefined {
	if (!isRecord(variables) || !isRecord(variables.body)) {
		return undefined;
	}
	const { at } = variables.body;
	return at instanceof Date ? at : undefined;
}
