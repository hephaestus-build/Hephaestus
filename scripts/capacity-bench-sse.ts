/** TEST ONLY. One synthetic identity and SSE subscription per user, on the isolated server's loopback. */
import { createWriteStream } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import { finished } from "node:stream/promises";

import { runCapacityChat } from "./lib/capacity-chat.ts";
import { installCapacitySignals } from "./lib/capacity-child.ts";
import { asStringArray, parseJson } from "./lib/json.ts";

const users = Number(process.env.COURSE_USERS ?? "40");
const destination = process.env.LOAD_SUMMARY_PATH;
if (
	![40, 80, 120].includes(users) ||
	destination === undefined ||
	!destination.endsWith("/summary.json")
) {
	throw new Error("Require 40|80|120 users and a summary.json destination");
}
const tokens = asStringArray(
	parseJson(await readFile("/bench/auth-tokens.json", "utf8")),
	"sessions",
).slice(0, users);
if (tokens.length !== users || new Set(tokens).size !== users) {
	throw new Error("Distinct synthetic sessions required");
}
const abort = new AbortController();
const removeSignals = installCapacitySignals(abort);
const stopAt = Date.parse(process.env.CAPACITY_STOP_AT ?? "");
if (!Number.isFinite(stopAt)) {
	throw new Error("CAPACITY_STOP_AT must be an operator-approved UTC deadline");
}
const deadlineTimer = setTimeout(
	() => {
		abort.abort();
	},
	Math.max(0, stopAt - Date.now()),
);
const events = createWriteStream(destination.replace("summary.json", "stream-events.jsonl"));
events.on("error", () => {
	abort.abort();
});
const result = await runCapacityChat({
	base: "http://127.0.0.1:8080/workspaces/capacity-bench-course/mentor",
	tokens,
	runLabel: process.env.CAPACITY_RUN_LABEL,
	signal: abort.signal,
	onEvent: (row) => {
		events.write(`${JSON.stringify(row)}\n`);
	},
});
events.end();
let streamLogComplete = true;
try {
	await finished(events);
} catch {
	streamLogComplete = false;
}
await writeFile(
	destination,
	JSON.stringify(
		{ ...result, streamLogComplete, qualified: result.qualified && streamLogComplete },
		null,
		2,
	),
);
removeSignals();
clearTimeout(deadlineTimer);
process.stdout.write(
	`${result.completedSessions}/${users} sessions; ${result.done}/${users * 3} DONE turns; qualified=${result.qualified}\n`,
);
process.exitCode = result.qualified && streamLogComplete ? 0 : 1;
