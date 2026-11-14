import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";

import type { SessionEntry } from "@earendil-works/pi-coding-agent";

import type { Archive, LoadedArchive } from "../../../main/resources/agent/context-folder.ts";
import {
	isRecord,
	type LinkToolName,
	type PartialSnapshot,
	type PartialUpdate,
	type SandboxRefusal,
	type StopReason,
	type TurnContext,
	type TurnEnd,
} from "../../../main/resources/agent/conversation-host-protocol.ts";
import {
	haltTurn,
	type HaltReason,
	leaseLost,
	type LinkAnswer,
	type ModelFactory,
	newTurnState,
	runTurn as runAttempt,
	type TurnApi,
} from "../../../main/resources/agent/turn.ts";

export const AGENT_DIR = mkdtempSync(nodePath.join(tmpdir(), "ask-turn-agent-"));
export const SITUATION = "<situation>\nNow: Thu 2026-10-08 09:12 UTC.\n</situation>";

/** A new, empty context folder: each turn has its own, so no test reads the files of another. */
function newFolder(): string {
	return mkdtempSync(nodePath.join(tmpdir(), "ask-turn-folder-"));
}

/** A tar archive of `files`, as the server builds one: paths relative to its root. */
export function tarOf(files: Readonly<Record<string, string>>): Buffer {
	const dir = mkdtempSync(nodePath.join(tmpdir(), "ask-archive-src-"));
	try {
		for (const [name, text] of Object.entries(files)) {
			mkdirSync(nodePath.dirname(nodePath.join(dir, name)), { recursive: true });
			writeFileSync(nodePath.join(dir, name), text);
		}
		const names = Object.keys(files);
		// An empty list is no member list for tar: it reads the names from an empty file instead.
		return execFileSync("tar", [
			"-c",
			"-f",
			"-",
			"-C",
			dir,
			...(names.length === 0 ? ["-T", "/dev/null"] : names),
		]);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
}

/** `bytes` as the server streams an archive, with its size and the SHA-256 it declares. */
export function archiveOf(
	bytes: Buffer,
	sha256 = createHash("sha256").update(bytes).digest("hex"),
): Archive {
	async function* chunks() {
		yield bytes;
	}
	return { bytes: chunks(), size: bytes.length, sha256 };
}

/** The documents of a turn's folder when a test names none. */
export const DOCUMENTS: Readonly<Record<string, string>> = {
	"INDEX.md":
		"# Context folder\n\n- `inputs/context/user.json`: who you talk to\n\n## Repositories you can load\n\n" +
		"- `repos/github.com/acme/api`\n",
	"FIELDS.md": "# Fields\n\n## user.json\n\n- `login`: their GitHub login\n",
	"inputs/context/user.json": '{"login":"ada"}\n',
};

/** The one repository that the fake worker loads, and its files. */
export const REPOSITORY_PATH = "repos/github.com/acme/api";
export const REPOSITORY: Readonly<Record<string, string>> = {
	"README.md": "# api\n",
	"src/money.ts": "export type Money = { cents: bigint };\n",
};
const REPOSITORY_SHA = "7044a8a0c0ffee0c0ffee0c0ffee0c0ffee0c0ff";

/** The worker's answer to a load: the repository at the ref asked for, or a refusal for any other path. */
function load(requested: string): LoadedArchive | SandboxRefusal {
	const [path = "", ref = "main"] = requested.split("@");
	if (path !== REPOSITORY_PATH) {
		return { ok: false, error: "NOT_VISIBLE", detail: `Hephaestus cannot read ${path}.` };
	}
	return {
		ok: true,
		path,
		resolved: { ref, sha: REPOSITORY_SHA, cite: `@${REPOSITORY_SHA.slice(0, 7)}` },
		note: "Staged acme/api with its history.",
		summary: ["ls"],
		archive: archiveOf(tarOf(REPOSITORY)),
		cancel: async () => undefined,
	};
}

/** A turn's worker endpoints, and what they received. */
export interface FakeWorker {
	/** The entries the turn's end stored. */
	readonly appended: SessionEntry[];
	readonly partials: PartialSnapshot[];
	/** How often the turn fetched its folder's documents, and each path it loaded. */
	readonly loads: string[];
	readonly searched: Record<string, unknown>[];
	/** Each id a tool that shows feedback asked the worker to admit. */
	readonly linked: [LinkToolName, string][];
	api: () => TurnApi;
	waitForEnd: () => Promise<TurnEnd>;
	/** The session as the ledger holds it after the turn: the path it started from and what the turn appended. */
	ledger: () => unknown[];
}

/** The snapshot text that `update` makes of `kept`, or null when it was made from another snapshot. */
export function applied(
	kept: { sequence: number; text: string } | null,
	update: PartialUpdate,
): string | null {
	if (update.baseSequence === 0) {
		return update.prefix === 0 && update.suffix === 0 ? update.text : null;
	}
	if (kept === null || kept.sequence !== update.baseSequence) {
		return null;
	}
	if (update.prefix + update.suffix > kept.text.length) {
		return null;
	}
	return (
		kept.text.slice(0, update.prefix) +
		update.text +
		kept.text.slice(kept.text.length - update.suffix)
	);
}

function isPartialSnapshot(value: unknown): value is PartialSnapshot {
	return (
		isRecord(value) &&
		typeof value.activity === "string" &&
		typeof value.stage === "string" &&
		Array.isArray(value.messages)
	);
}

/** Rebuilds each partial answer from its update, as a reader does: every update must apply to the one before it. */
export function partialReader(): (update: PartialUpdate) => PartialSnapshot {
	let kept: { sequence: number; text: string } | null = null;
	return (update) => {
		const text = applied(kept, update);
		assert.ok(
			text !== null,
			`a partial update applies to the one before it: ${JSON.stringify(update)}`,
		);
		kept = { sequence: update.sequence, text };
		const snapshot: unknown = JSON.parse(text);
		assert.ok(isPartialSnapshot(snapshot), `a partial answer is a snapshot: ${text}`);
		return snapshot;
	};
}

export function isSessionEntry(value: unknown): value is SessionEntry {
	return isRecord(value) && typeof value.type === "string" && typeof value.id === "string";
}

/**
 * A worker for one turn. Its folder holds `documents`, paths relative to the folder, and it loads `REPOSITORY` for
 * `REPOSITORY_PATH`.
 */
export function fakeWorker(
	context: TurnContext,
	documents: Readonly<Record<string, string>> = DOCUMENTS,
	onPartial?: (snapshot: PartialSnapshot) => void,
	link: (tool: LinkToolName, id: string) => Promise<LinkAnswer> = async () => ({ admitted: true }),
): FakeWorker {
	const appended: SessionEntry[] = [];
	const partials: PartialSnapshot[] = [];
	const decode = partialReader();

	const loads: string[] = [];
	const searched: Record<string, unknown>[] = [];
	const linked: [LinkToolName, string][] = [];
	const ended = Promise.withResolvers<TurnEnd>();
	let open = true;
	// Like the worker, which answers 409 once the attempt ended.
	const check = () => {
		if (!open) {
			throw leaseLost("the turn ended");
		}
	};
	return {
		appended,
		partials,
		loads,
		searched,
		linked,
		api: () => ({
			context: async () => ({
				...structuredClone(context),
				documents: tarOf(documents).toString("base64"),
			}),
			folder: {
				document: async () => null,
				load: async (path) => {
					loads.push(path);
					return load(path);
				},
			},
			link: async (tool, id) => {
				linked.push([tool, id]);
				return link(tool, id);
			},
			search: async (query) => {
				searched.push(query);
				return { hits: [] };
			},
			partial: async (update) => {
				check();
				const snapshot = decode(update);
				partials.push(snapshot);
				onPartial?.(snapshot);
			},
			end: async (end) => {
				check();
				open = false;
				appended.push(...structuredClone(end.entries).filter(isSessionEntry));
				ended.resolve(end);
			},
		}),
		waitForEnd: async () => ended.promise,
		ledger: () => [...structuredClone(context.entries), ...structuredClone(appended)],
	};
}

export function contextOf(
	entries: unknown[],
	prompt: string,
	systemPrompt = "You are Hephaestus.",
): TurnContext {
	return {
		sessionId: crypto.randomUUID(),
		entries,
		prompt,
		situation: SITUATION,
		contextFolder: true,
		systemPrompt,
		documents: tarOf(DOCUMENTS).toString("base64"),
		model: {
			apiProtocol: "openai-completions",
			modelId: "faux",
			contextWindow: null,
			maxOutputTokens: null,
			reasoningEffort: null,
		},
	};
}

/** One attempt of a turn that runs in its own folder, as a sandbox runs it. */
export interface StartedTurn {
	turnId: string;
	/** The turn's context folder, its cwd. */
	folder: string;
	stop: (reason: HaltReason) => void;
	/** Whether the worker took the turn's end, once the attempt ran. */
	done: Promise<boolean>;
}

/**
 * Starts one attempt of a turn on `api`. An `api` that is a function gets the stop that its calls report, as
 * `httpTurnApi` does.
 */
export function startTurn(
	api: TurnApi | ((onStop: (reason: StopReason) => void) => TurnApi),
	models: ModelFactory,
	partialIntervalMs = 10,
): StartedTurn {
	const turnId = crypto.randomUUID();
	const state = newTurnState(1);
	const stop = (reason: HaltReason) => haltTurn(turnId, state, reason);
	const folder = newFolder();
	const done = runAttempt(
		turnId,
		typeof api === "function" ? api(stop) : api,
		"turn-token",
		state,
		{ cwd: folder, agentDir: AGENT_DIR, models, partialIntervalMs },
	);
	return { turnId, folder, stop, done };
}

/** Runs one turn on `worker`, or on `api` when a test changes what the worker answers, and returns its end. */
export async function runTurn(
	worker: FakeWorker,
	models: ModelFactory,
	partialIntervalMs = 10,
	api: TurnApi = worker.api(),
): Promise<TurnEnd> {
	const turn = startTurn(api, models, partialIntervalMs);
	const end = await worker.waitForEnd();
	await turn.done;
	return end;
}

/** One tool call of a stored turn: its tool, its arguments, and the result that the model read. */
export interface ToolRun {
	name: string;
	arguments: Record<string, unknown>;
	text: string;
	isError: boolean;
}

/** The tool calls among `entries` that have a result, in the order of the calls. */
export function toolRuns(entries: readonly SessionEntry[]): ToolRun[] {
	const results = new Map<string, { text: string; isError: boolean }>();
	for (const entry of entries) {
		if (entry.type === "message" && entry.message.role === "toolResult") {
			const text = entry.message.content
				.map((block) => (block.type === "text" ? block.text : ""))
				.join("");
			results.set(entry.message.toolCallId, { text, isError: entry.message.isError });
		}
	}
	return entries.flatMap((entry) =>
		entry.type === "message" && entry.message.role === "assistant"
			? entry.message.content.flatMap((block) => {
					const result = block.type === "toolCall" ? results.get(block.id) : undefined;
					return block.type === "toolCall" && result !== undefined
						? [{ name: block.name, arguments: block.arguments, ...result }]
						: [];
				})
			: [],
	);
}
