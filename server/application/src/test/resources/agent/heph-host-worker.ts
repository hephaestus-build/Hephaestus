import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";

import type { SessionEntry } from "@earendil-works/pi-coding-agent";

import {
	isRecord,
	type LinkToolName,
	type PartialSnapshot,
	type SandboxExecAnswer,
	type SandboxExecRequest,
	type SandboxLoadAnswer,
	type SandboxLoadRequest,
	type SandboxReadAnswer,
	type SandboxReadRequest,
	type TurnContext,
	type TurnEnd,
} from "../../../main/resources/agent/heph-host-protocol.ts";
import {
	createHephHost,
	leaseLost,
	type LinkAnswer,
	type ModelFactory,
	type TurnApi,
} from "../../../main/resources/agent/heph-host.ts";

export const AGENT_DIR = mkdtempSync(nodePath.join(tmpdir(), "heph-host-agent-"));
export const CWD = mkdtempSync(nodePath.join(tmpdir(), "heph-host-cwd-"));
export const SITUATION = "<situation>\nNow: Thu 2026-10-08 09:12 UTC.\n</situation>";

/** A turn's worker endpoints, and what they received. */
export interface FakeWorker {
	/** The entries the turn's end stored. */
	readonly appended: SessionEntry[];
	readonly partials: PartialSnapshot[];
	/** Each request of the turn's sandbox calls. */
	readonly reads: SandboxReadRequest[];
	readonly execs: SandboxExecRequest[];
	readonly loads: SandboxLoadRequest[];
	readonly searched: Record<string, unknown>[];
	/** Each id a tool that shows feedback asked the worker to admit. */
	readonly linked: [LinkToolName, string][];
	api: () => TurnApi;
	waitForEnd: () => Promise<TurnEnd>;
	/** The session as the ledger holds it after the turn: the path it started from and what the turn appended. */
	ledger: () => unknown[];
}

export function isSessionEntry(value: unknown): value is SessionEntry {
	return isRecord(value) && typeof value.type === "string" && typeof value.id === "string";
}

/** A loaded repository, as the worker answers a load. */
const LOADED: SandboxLoadAnswer = {
	ok: true,
	path: "repos/github.com/acme/api",
	resolved: { ref: "pull/42", sha: "7044a8a0c0ffee0c0ffee0c0ffee0c0ffee0c0ff", cite: "@7044a8a" },
	note: "Staged pull request #42 of acme/api with its history.",
};

export function ran(
	output: string,
	exitCode: number | null = 0,
): Extract<SandboxExecAnswer, { ok: true }> {
	return { ok: true, exitCode, output, truncated: false, timedOut: false, fullOutputPath: null };
}

export function fakeWorker(
	context: TurnContext,
	read: (request: SandboxReadRequest, signal: AbortSignal) => Promise<SandboxReadAnswer> = async (
		request,
	) => ({ ok: true, text: `contents of ${request.path}` }),
	onPartial?: (snapshot: PartialSnapshot) => void,
	link: (tool: LinkToolName, id: string) => Promise<LinkAnswer> = async () => ({ admitted: true }),
): FakeWorker {
	const appended: SessionEntry[] = [];
	const partials: PartialSnapshot[] = [];
	const reads: SandboxReadRequest[] = [];
	const execs: SandboxExecRequest[] = [];
	const loads: SandboxLoadRequest[] = [];
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
		reads,
		execs,
		loads,
		searched,
		linked,
		api: () => ({
			context: async () => structuredClone(context),
			read: async (request, signal) => {
				reads.push(request);
				return read(request, signal);
			},
			exec: async (request) => {
				execs.push(request);
				return ran(`ran ${request.command}`);
			},
			load: async (request) => {
				loads.push(request);
				return LOADED;
			},
			link: async (tool, id) => {
				linked.push([tool, id]);
				return link(tool, id);
			},
			search: async (query) => {
				searched.push(query);
				return { hits: [] };
			},
			partial: async (snapshot) => {
				check();
				partials.push(structuredClone(snapshot));
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

export function contextOf(entries: unknown[], prompt: string): TurnContext {
	return {
		sessionId: crypto.randomUUID(),
		entries,
		prompt,
		situation: SITUATION,
		contextFolder: true,
		model: {
			apiProtocol: "openai-completions",
			modelId: "faux",
			contextWindow: null,
			maxOutputTokens: null,
			reasoningEffort: null,
		},
	};
}

export function hostFor(
	worker: FakeWorker,
	models: ModelFactory,
	partialIntervalMs = 10,
	systemPrompt = "You are Hephaestus.",
) {
	return createHephHost({
		cwd: CWD,
		agentDir: AGENT_DIR,
		systemPrompt,
		turnApi: () => worker.api(),
		models,
		partialIntervalMs,
	});
}

export async function runTurn(
	worker: FakeWorker,
	models: ModelFactory,
	partialIntervalMs = 10,
	systemPrompt?: string,
): Promise<TurnEnd> {
	const host = hostFor(worker, models, partialIntervalMs, systemPrompt);
	host.start(crypto.randomUUID(), 1, "turn-token");
	const end = await worker.waitForEnd();
	await host.idle();
	return end;
}
