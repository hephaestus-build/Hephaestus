/**
 * The messages between the precompute runner and a script's child process. The runner trusts its own
 * messages; it parses every message a child sends, because the child runs untrusted code.
 */
import { z } from "zod";

import { modelSlotSchema, type ModelSlot, type Reason, type SlotAnswer } from "./contract.ts";
import type { GrepMatch } from "./grep.ts";
import type { ArtifactMetadata, DiffFile } from "./types.ts";

export interface ChildJob {
	modulePath: string;
	slug: string;
	repoPath: string;
	diffFiles: Map<string, DiffFile>;
	metadata: ArtifactMetadata;
	contextDir: string;
	changeDir: string;
	contextReference: string;
	now: string;
	/** Epoch milliseconds at which the script's model calls and signal end; its process ends later. */
	deadline: number;
}

/** Messages the runner sends to a child. */
export type RunnerMessage =
	| { kind: "job"; job: ChildJob }
	| { kind: "grep"; id: number; matches: GrepMatch[] }
	| { kind: "grep-failed"; id: number; message: string }
	/** The models this script may call: the slots it declared that have a bound model. */
	| { kind: "meta-accepted"; models: Partial<Record<ModelSlot, string>>; tokens: number }
	/** What the runner's model for the requested slot returned. */
	| { kind: "model"; id: number; answer: SlotAnswer; tokensLeft: number }
	| { kind: "model-failed"; id: number; reason: Reason; message: string; tokensLeft: number };

/** The longest log line and call label a child may send. */
export const MAX_LOG_CHARS = 2000;
export const MAX_LABEL_CHARS = 200;

export const childMessageSchema = z.discriminatedUnion("kind", [
	z.strictObject({
		kind: z.literal("grep"),
		id: z.int(),
		pattern: z.string(),
		dir: z.string(),
		opts: z.strictObject({
			glob: z.string().optional(),
			maxResults: z.int().optional(),
			fixedString: z.boolean().optional(),
		}),
	}),
	z.strictObject({ kind: z.literal("meta"), meta: z.unknown() }),
	z.strictObject({
		kind: z.literal("model"),
		id: z.int(),
		slot: modelSlotSchema,
		label: z.string().max(MAX_LABEL_CHARS),
		options: z.unknown(),
	}),
	z.strictObject({ kind: z.literal("cancel"), id: z.int() }),
	z.strictObject({ kind: z.literal("log"), text: z.string().max(MAX_LOG_CHARS) }),
	z.strictObject({ kind: z.literal("result"), value: z.unknown() }),
	z.strictObject({ kind: z.literal("failed"), message: z.string() }),
]);
export type ChildMessage = z.infer<typeof childMessageSchema>;
