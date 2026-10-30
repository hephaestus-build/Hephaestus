/**
 * The messages between the program host and the child process of a custom practice program. The host
 * trusts its own messages. It parses every message a child sends, because the child runs untrusted
 * code. Every request carries a call id; the host answers each once.
 */
import { z } from "zod";

import type { GrepMatch } from "../../kits/scm/grep.ts";
import type { DiffFile } from "../../kits/scm/types.ts";
import {
	modelSlotSchema,
	REASONS,
	CANDIDATE_LIMITS,
	type Candidate,
	type Citation,
	type ModelSlot,
	type PracticeInputs,
	type SlotAnswer,
} from "./contract.ts";
import type { UnratedReason } from "./practice-script.ts";

/** The trees and the change of the reviewed work, as the host pinned them. Null without a captured tree. */
export interface JobFiles {
	head: { commit: string };
	change: { base: string; head: string; files: Map<string, DiffFile> } | null;
}

/** The immutable data of one practice's execution: the host's, never the program's. */
export interface ProgramJob {
	slug: string;
	inputs: PracticeInputs;
	files: JobFiles | null;
	now: string;
	/** Epoch milliseconds at which the practice's execution ends. */
	deadline: number;
}

export interface ChildJob extends ProgramJob {
	modulePath: string;
}

export interface ReadValues {
	read:
		| { status: "FOUND"; text: string; truncated: boolean }
		| { status: "BINARY" }
		| { status: "ABSENT" };
	list: { paths: string[]; truncated: boolean };
	grep: { matches: GrepMatch[]; truncated: boolean };
	record: { status: "FOUND"; text: string } | { status: "ABSENT" };
	cite: Citation;
}
export type ReadValue = ReadValues[keyof ReadValues];

/** Why the host stopped the program: its deadline, the attempt's budget, or a cancellation. */
export type AbortReason = "deadline" | "budget" | "cancelled";

export type HostMessage =
	| { kind: "job"; job: ChildJob }
	| {
			kind: "meta-accepted";
			models: Partial<Record<ModelSlot, string>>;
			forcedToolChoice: boolean;
			tokens: number;
	  }
	| { kind: "model"; id: number; answer: SlotAnswer; tokensLeft: number }
	| { kind: "model-failed"; id: number; reason: UnratedReason; message: string; tokensLeft: number }
	| { kind: "read-result"; id: number; value: ReadValue }
	| { kind: "read-failed"; id: number; reason: UnratedReason; message: string }
	| { kind: "review-result"; id: number; candidate: Candidate }
	| { kind: "review-failed"; id: number; reason: UnratedReason; message: string }
	| { kind: "abort"; reason: AbortReason };

/** The longest log line and call label a child may send. */
export const MAX_LOG_CHARS = 2000;
export const MAX_LABEL_CHARS = 200;
export const MAX_IPC_MESSAGE_BYTES = 524_288;

const callId = z.int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const lineNumber = z.int().min(1).max(2_147_483_647);
const shortText = z.string().min(1).max(CANDIDATE_LIMITS.pathChars);

const readRequestSchema = z.union([
	z.strictObject({
		op: z.literal("read"),
		path: shortText,
		offsetLine: lineNumber.optional(),
		maxLines: lineNumber.optional(),
	}),
	z.strictObject({ op: z.literal("list"), glob: shortText.optional() }),
	z.strictObject({
		op: z.literal("grep"),
		pattern: shortText,
		glob: shortText.optional(),
		maxResults: z.int().positive().max(1000).optional(),
		fixedString: z.boolean().optional(),
	}),
	z.strictObject({ op: z.literal("record"), name: shortText }),
	z.strictObject({
		op: z.literal("cite"),
		source: z.literal("tree"),
		path: shortText,
		startLine: lineNumber,
		endLine: lineNumber.optional(),
	}),
	z.strictObject({
		op: z.literal("cite"),
		source: z.literal("change"),
		path: shortText,
		side: z.enum(["OLD", "NEW"]),
		startLine: lineNumber,
		endLine: lineNumber.optional(),
	}),
	z.strictObject({
		op: z.literal("cite"),
		source: z.literal("record"),
		name: shortText,
		startLine: lineNumber,
		endLine: lineNumber.optional(),
	}),
]);

/** A governed read of the reviewed work, answered from its pinned captures. */
export type ReadRequest = z.infer<typeof readRequestSchema>;

export const childMessageSchema = z.discriminatedUnion("kind", [
	z.strictObject({ kind: z.literal("meta"), meta: z.unknown() }),
	z.strictObject({
		kind: z.literal("model"),
		id: callId,
		slot: modelSlotSchema,
		label: z.string().max(MAX_LABEL_CHARS),
		options: z.unknown(),
	}),
	z.strictObject({ kind: z.literal("read"), id: callId, request: readRequestSchema }),
	/** The options are parsed with the review input's own schema when the host answers the request. */
	z.strictObject({ kind: z.literal("review"), id: callId, options: z.unknown() }),
	z.strictObject({ kind: z.literal("cancel"), id: callId }),
	z.strictObject({ kind: z.literal("log"), text: z.string().max(MAX_LOG_CHARS) }),
	z.strictObject({ kind: z.literal("result"), value: z.unknown() }),
	z.strictObject({
		kind: z.literal("failed"),
		message: z.string(),
		reason: z.enum([...REASONS, "cancelled"]).optional(),
	}),
]);
export type ChildMessage = z.infer<typeof childMessageSchema>;
