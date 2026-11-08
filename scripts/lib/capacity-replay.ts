import { createHash } from "node:crypto";

import { asArray, asRecord, asString, isRecord } from "./json.ts";

export interface ReplaySample {
	ttftMs: number;
	durationMs: number;
	inputTokens: number;
	outputTokens: number;
	status: 200 | 429;
}

function nonnegative(value: unknown, name: string): number {
	if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
		throw new Error(`${name} must be a nonnegative safe integer`);
	}
	return value;
}

export function parseReplay(value: unknown) {
	const document = asRecord(value, "replay");
	const provenance = asString(document.provenance, "provenance");
	if (provenance !== "assumed" && provenance !== "measured") {
		throw new Error("Replay provenance must be assumed or measured");
	}
	const source = asString(document.source, "source");
	if (source.trim() === "") {
		throw new Error("Replay source is required");
	}
	const samples = asArray(document.samples, "samples").map((entry): ReplaySample => {
		const row = asRecord(entry, "sample");
		const ttftMs = nonnegative(row.ttftMs, "ttftMs");
		const durationMs = nonnegative(row.durationMs, "durationMs");
		if (durationMs < ttftMs || (row.status !== 200 && row.status !== 429)) {
			throw new Error("Invalid replay duration or status");
		}
		return {
			ttftMs,
			durationMs,
			inputTokens: nonnegative(row.inputTokens, "inputTokens"),
			outputTokens: nonnegative(row.outputTokens, "outputTokens"),
			status: row.status,
		};
	});
	if (samples.length === 0) {
		throw new Error("Replay requires samples");
	}
	return { provenance, source, samples };
}

/** The content hash makes replay independent of arrival order and worker scheduling. */
export function replaySample(samples: readonly ReplaySample[], key: string): ReplaySample {
	const index = createHash("sha256").update(key).digest().readUInt32BE(0) % samples.length;
	const sample = samples[index];
	if (!sample) {
		throw new Error("Replay requires samples");
	}
	return sample;
}

export function syntheticCorrelation(raw: string): string | null {
	return [...raw.matchAll(/capacity-bench:(?<id>[a-f0-9-]{36})/gu)].at(-1)?.groups?.id ?? null;
}

/** Ignore earlier runs in history; the highest turn index is the current turn of this synthetic user. */
export function logicalReplayKey(raw: string, runLabel: string): string | null {
	const markers = [
		...raw.matchAll(/capacity-replay:(?<run>[a-z0-9-]+):(?<user>\d+):(?<turn>[0-2])/gu),
	].filter((match) => match.groups?.run === runLabel);
	const users = new Set(markers.map((match) => match.groups?.user));
	if (users.size !== 1) {
		return null;
	}
	const latest = markers.toSorted((a, b) => Number(b.groups?.turn) - Number(a.groups?.turn))[0]
		?.groups;
	return latest?.user === undefined || latest.turn === undefined
		? null
		: `${latest.user}:${latest.turn}`;
}

export function selectReplayCall(
	raw: string,
	samples: readonly ReplaySample[],
	runLabel: string | undefined,
	ordinals: Map<string, number>,
) {
	const logicalKey = runLabel === undefined ? null : logicalReplayKey(raw, runLabel);
	if (runLabel !== undefined && logicalKey === null) {
		throw new Error("Missing current-run replay key");
	}
	if (ordinals.size >= 100_000) {
		throw new Error("Replay call-state bound reached");
	}
	let ordinal: number | null = null;
	let key = createHash("sha256").update(raw).digest("hex");
	if (logicalKey !== null) {
		ordinal = (ordinals.get(logicalKey) ?? 0) + 1;
		ordinals.set(logicalKey, ordinal);
		key = `${logicalKey}:${ordinal}`;
	}
	const marker = syntheticCorrelation(raw);
	return {
		sample: replaySample(samples, key),
		logicalKey,
		ordinal,
		marker,
		requestKey: logicalKey ?? marker ?? key,
	};
}

export function claimReplayTool(sent: Set<string>, key: string): boolean {
	if (sent.has(key)) {
		return false;
	}
	if (sent.size >= 100_000) {
		throw new Error("Replay tool-state bound reached");
	}
	sent.add(key);
	return true;
}

export function sensitivityGrid() {
	return [1, 3, 8].flatMap((ttftSeconds) =>
		[500, 1500, 4000].flatMap((outputTokens) =>
			[0, 5, 20].map((rate429) => ({
				name: `ttft-${ttftSeconds}-output-${outputTokens}-429-${rate429}`,
				provenance: "assumed",
				source: "Sensitivity grid; input 1200 tokens and stream rate 50 tokens/s are assumptions",
				samples: Array.from({ length: 100 }, (_, index) => ({
					ttftMs: ttftSeconds * 1000,
					durationMs: ttftSeconds * 1000 + (outputTokens / 50) * 1000,
					inputTokens: 1200,
					outputTokens,
					status: index < rate429 ? 429 : 200,
				})),
			})),
		),
	);
}

/**
 * The most `bash` calls of one turn-roll turn. At 30 s each, they fill about the 900 s that a turn
 * may run, and they stay below the 40 model calls of a turn (`MAX_TURN_MODEL_CALLS` in
 * `heph-host.ts`), with room for retries.
 */
export const ROLL_CALL_LIMIT = 28;

/** The output that the turn-roll command of call `ordinal` prints. */
export const rollOutput = (ordinal: number) => `rolled-${ordinal}`;

function messageText(content: unknown): string {
	if (typeof content === "string") {
		return content;
	}
	return Array.isArray(content)
		? content
				.map((part: unknown) => (isRecord(part) && typeof part.text === "string" ? part.text : ""))
				.join("")
		: "";
}

/**
 * The turn-roll script for one model call: a `bash` command that runs for 30 s, again after each
 * result, until the drill releases the turn. Then a text that repeats each output the commands
 * printed, so the answer shows which `bash` calls succeeded.
 */
export function rollStep(
	messages: readonly unknown[],
	released: boolean,
): { command: string } | { text: string } {
	const rows = messages
		.map((message) => asRecord(message, "message"))
		// The loop sends the turn's situation as the last message, after the results
		// (docs/contributor/unified-pi-runtime.mdx § Context of a turn).
		.filter((row) => !(row.role === "user" && messageText(row.content).startsWith("<situation>")));
	const question = rows.findLastIndex((row) => row.role === "user");
	const results = rows.slice(question + 1).filter((row) => row.role === "tool");
	if (!released && results.length < ROLL_CALL_LIMIT) {
		// Below the 60 s that one command may run (`MAX_COMMAND_MS` in `heph-tools.ts`).
		return { command: `sleep 30; echo ${rollOutput(results.length + 1)}` };
	}
	const outputs = results.flatMap((row) => messageText(row.content).match(/rolled-\d+/gu) ?? []);
	return { text: `The commands printed: ${outputs.join(" ")}.` };
}
