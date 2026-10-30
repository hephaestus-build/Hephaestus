/** TEST ONLY. Run the existing k6 scenario inside the isolated server Pod's driver container. */
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { parseArgs } from "node:util";

import {
	capacityStopRequested,
	recordCapacityChild,
	stopCapacityChild,
} from "./lib/capacity-child.ts";
import { asRecord, asStringArray, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const { values } = parseArgs({
	options: {
		label: { type: "string" },
		scenario: { type: "string", default: "S3" },
		mode: { type: "string", default: "smoke" },
		"quiet-window": { type: "string" },
	},
});
if (
	values.label === undefined ||
	!/^[a-z0-9-]+$/u.test(values.label) ||
	!["smoke", "recorded"].includes(values.mode)
) {
	throw new Error("Supply a fresh --label and --mode smoke|recorded");
}
if (values.scenario !== "S3") {
	throw new Error(
		"This runner starts with S3; review and redelivery fixtures require separate qualification",
	);
}
const directory = path.join("/bench/results", values.label);
const sessions = asStringArray(
	parseJson(await readFile("/bench/auth-tokens.json", "utf8")),
	"sessions",
);
let quietWindow: Record<string, unknown> | null = null;
if (values.mode === "recorded") {
	if (values["quiet-window"] === undefined) {
		throw new Error("A recorded run requires --quiet-window metadata");
	}
	quietWindow = asRecord(parseJson(await readFile(values["quiet-window"], "utf8")), "quiet window");
	const exclusive =
		quietWindow.releaseIdleConfirmed === true && quietWindow.sharedRolesStopped === true;
	const isolated = quietWindow.scope === "isolated-release" && quietWindow.mergeQueueEmpty === true;
	if (!exclusive && !isolated) {
		throw new Error(
			"Recorded run requires an exclusive window or an isolated-release empty-queue check",
		);
	}
}
await mkdir("/bench/results", { recursive: true });
await mkdir(directory, { mode: 0o700 });
const source = await readFile("/bench/load-tests/course.js", "utf8");
const sourceSha256 = createHash("sha256").update(source).digest("hex");
const scriptFiles = await readdir("/bench/scripts", { recursive: true });
const loadFiles = await readdir("/bench/load-tests", { recursive: true });
const sourceFiles = [
	...scriptFiles.filter((file) => file.endsWith(".ts")).map((file) => `scripts/${file}`),
	...loadFiles.filter((file) => file.endsWith(".js")).map((file) => `load-tests/${file}`),
];
const sourceHashes = Object.fromEntries(
	await Promise.all(
		sourceFiles.map(
			async (file) =>
				[
					file,
					createHash("sha256")
						.update(await readFile(`/bench/${file}`))
						.digest("hex"),
				] satisfies [string, string],
		),
	),
);
const env = {
	...process.env,
	BASE_URL: "http://127.0.0.1:8080",
	API_PREFIX: "",
	WORKSPACE_SLUG: "capacity-bench-course",
	AUTH_TOKEN: sessions[0],
	AUTH_TOKENS: sessions.join(","),
	ARTIFACT_IDS: "1,2",
	WEBHOOK_SECRET: "synthetic-unused-in-s3",
	THREADS_PER_DEVELOPER: "0",
	COURSE_SCENARIO: values.scenario,
	LOAD_SUMMARY_PATH: path.join(directory, "summary.json"),
	CAPACITY_RUN_LABEL: values.label,
};
const executable = "/bench/k6-v1.2.3-linux-amd64/k6";
const inspected = spawnSync(
	executable,
	["inspect", "--include-system-env-vars", "/bench/load-tests/course.js"],
	{ env, encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
);
await writeFile(path.join(directory, "inspect.stderr"), inspected.stderr);
if (inspected.status !== 0) {
	throw new Error("k6 inspect failed before application load");
}
const options = asRecord(parseJson(inspected.stdout), "options");
const metadata = {
	scenario: values.scenario,
	mode: values.mode,
	sourceSha256,
	sourceHashes,
	quietWindow,
	startedAt: new Date().toISOString(),
	scenarios: options.scenarios,
	thresholds: options.thresholds,
	providerProvenance: process.env.CAPACITY_PROVIDER_PROVENANCE ?? "assumed",
	identities: sessions.length,
	driver: process.env.COURSE_SSE === "1" ? "typed-typescript-sse" : "k6-poll",
	users: Number(process.env.COURSE_USERS ?? "40"),
	turnAdmission: process.env.CAPACITY_TURN_ADMISSION ?? null,
	toolMode: process.env.CAPACITY_TOOL_MODE ?? "mixed-read",
};
await writeFile(path.join(directory, "run.json"), JSON.stringify(metadata, null, 2));
if (await capacityStopRequested("/bench", values.label)) {
	await writeFile(
		path.join(directory, "run.json"),
		JSON.stringify(
			{ ...metadata, finishedAt: new Date().toISOString(), exitCode: 1, stoppedBeforeLoad: true },
			null,
			2,
		),
	);
	throw new Error("Stop requested before load");
}
const child = spawn(
	process.env.COURSE_SSE === "1" ? process.execPath : executable,
	process.env.COURSE_SSE === "1"
		? ["/bench/scripts/capacity-bench-sse.ts"]
		: ["run", "--summary-trend-stats=avg,min,med,max,p(95),p(99)", "/bench/load-tests/course.js"],
	{
		env,
		stdio: ["ignore", "pipe", "pipe"],
	},
);
const completion = Promise.withResolvers<{
	status: number | null;
	signal: NodeJS.Signals | null;
}>();
child.once("error", completion.reject);
child.once("close", (status, signal) => {
	completion.resolve({ status, signal });
});
const stdout = createWriteStream(path.join(directory, "driver.log"));
const stderr = createWriteStream(path.join(directory, "driver.stderr"));
child.stdout.pipe(stdout);
child.stderr.pipe(stderr);
async function recordAndCheck() {
	const { label } = values;
	if (label === undefined) {
		throw new Error("Missing run label");
	}
	try {
		if (child.pid !== undefined) {
			await recordCapacityChild("/bench", label, child.pid);
		}
		if (await capacityStopRequested("/bench", label)) {
			await stopCapacityChild("/bench", label);
		}
	} catch (error) {
		child.kill("SIGTERM");
		await completion.promise;
		throw error;
	}
}
const [result] = await Promise.all([completion.promise, recordAndCheck()]);
await writeFile(
	path.join(directory, "run.json"),
	JSON.stringify(
		{
			...metadata,
			finishedAt: new Date().toISOString(),
			exitCode: result.status,
			signal: result.signal,
		},
		null,
		2,
	),
);
process.stdout.write(`${directory}: k6 exit ${result.status ?? "interrupted"}\n`);
process.exitCode = result.status ?? 1;
