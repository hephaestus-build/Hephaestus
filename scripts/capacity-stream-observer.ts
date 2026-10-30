/** Read-only companion to k6: observe synthetic SSE payloads without generating arrivals. */
import { readFile } from "node:fs/promises";
import { parseArgs } from "node:util";

import { CapacityEvents } from "./lib/capacity-events.ts";
import { asRecord, asStringArray, isRecord, parseJson } from "./lib/json.ts";

const { values } = parseArgs({ options: { seconds: { type: "string", default: "1200" } } });
const seconds = Number(values.seconds);
if (!Number.isSafeInteger(seconds) || seconds < 1) {
	throw new Error("--seconds must be a positive integer");
}
const sessions = asStringArray(
	parseJson(await readFile("/bench/auth-tokens.json", "utf8")),
	"sessions",
);
const abort = new AbortController();
const timer = setTimeout(() => {
	abort.abort();
}, seconds * 1000);
const seen = new Map<string, Set<string>>();
const log = (row: Record<string, unknown>) =>
	process.stdout.write(`${JSON.stringify({ at: Date.now(), ...row })}\n`);
for (const signal of ["SIGTERM", "SIGINT"] as const) {
	process.once(signal, () => {
		abort.abort();
	});
}
try {
	await Promise.all(
		sessions.map(async (token, index) => {
			const response = await fetch(
				"http://127.0.0.1:8080/workspaces/capacity-bench-course/mentor/events",
				{
					headers: { authorization: `Bearer ${token}` },
					signal: abort.signal,
				},
			);
			if (!response.ok || response.body === null) {
				throw new Error(`Synthetic stream ${index}: HTTP ${response.status}`);
			}
			const events = new CapacityEvents();
			let first = true;
			for await (const chunk of response.body) {
				if (first) {
					log({ phase: "subscription_first_body_byte", stream: index });
					first = false;
				}
				events.push(chunk, (event, raw) => {
					if (event !== "turn") {
						return;
					}
					const turn = asRecord(parseJson(raw), "turn");
					if (typeof turn.requestId !== "string" || !/^[a-f0-9-]{36}$/u.test(turn.requestId)) {
						return;
					}
					const phases = seen.get(turn.requestId) ?? new Set<string>();
					const record = (phase: string) => {
						if (!phases.has(phase)) {
							phases.add(phase);
							log({ phase, requestId: turn.requestId, status: turn.status });
						}
					};
					record("first_turn_event");
					if (turn.status === "RUNNING") {
						record("running");
					}
					if (isRecord(turn.partial)) {
						record("first_partial");
						if (typeof turn.partial.text === "string" && turn.partial.text !== "") {
							record("first_text");
						}
					}
					if (
						typeof turn.status === "string" &&
						["DONE", "FAILED", "STOPPED"].includes(turn.status)
					) {
						record("end");
					}
					seen.set(turn.requestId, phases);
				});
			}
			if (!abort.signal.aborted) {
				throw new Error(`Synthetic stream ${index} ended before the observation window`);
			}
		}),
	);
} catch (error) {
	if (!abort.signal.aborted) {
		throw error;
	}
} finally {
	clearTimeout(timer);
	abort.abort();
}
