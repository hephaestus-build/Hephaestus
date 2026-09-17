import { spawnSync } from "node:child_process";
import { copyFile, glob, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { asRecord, asString, asStringArray, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

export const k6Image =
	"grafana/k6:1.2.3@sha256:4f82892217f3110cb233e2b2622bcc97fabc70f14bd241fbfbfe7305105c68aa";
const dockerUser =
	process.getuid && process.getgid ? ["--user", `${process.getuid()}:${process.getgid()}`] : [];
const inputs = [
	"BASE_URL",
	"WEBHOOK_SECRET",
	"AUTH_TOKEN",
	"WORKSPACE_SLUG",
	"ARTIFACT_IDS",
	"WEBHOOK_RATE",
	"DURATION",
	"PRE_ALLOCATED_VUS",
	"MAX_VUS",
	"WEBHOOK_PADDING_BYTES",
	"MENTOR_VUS",
	"REVIEW_VUS",
	"REVIEW_REQUESTS",
	"REVIEW_MAX_DURATION",
	"REVIEW_START_TIME",
	"REVIEW_TIMEOUT_SECONDS",
];
const secrets = new Set(["WEBHOOK_SECRET", "AUTH_TOKEN"]);
const limits = ["SANDBOX_API_MAX_REQUEST_BYTES", "SANDBOX_API_REQUESTS_PER_MINUTE"];

// Only the detection+mentor workload reaches the sandbox gateway, so only its result depends on the
// gateway's deployed limits.
const gatewayLimits = (scenario: string) => (scenario === "detection-mentor" ? limits : []);

export function configuration(scenario: string, env: NodeJS.ProcessEnv) {
	if (scenario !== "webhook-burst" && scenario !== "detection-mentor") {
		throw new Error("Unknown load scenario");
	}
	const recorded = gatewayLimits(scenario);
	const required = [
		"BASE_URL",
		...recorded,
		...(scenario === "webhook-burst"
			? ["WEBHOOK_SECRET"]
			: ["AUTH_TOKEN", "WORKSPACE_SLUG", "ARTIFACT_IDS"]),
	];
	for (const name of required) {
		const value = env[name]?.trim();
		if (!isSet(value)) {
			throw new Error(`${name} is required`);
		}
	}
	const url = new URL(env.BASE_URL ?? "");
	if (
		!["http:", "https:"].includes(url.protocol) ||
		url.username ||
		url.password ||
		url.search ||
		url.hash
	) {
		throw new Error("BASE_URL must be an HTTP(S) URL without credentials, query or fragment");
	}
	for (const name of recorded) {
		if (!/^[1-9]\d*$/u.test(env[name] ?? "") || !Number.isSafeInteger(Number(env[name]))) {
			throw new Error(`${name} must be a positive safe integer`);
		}
	}
	return {
		scenario,
		image: k6Image,
		inputs: Object.fromEntries(
			[...inputs, ...recorded]
				.filter((key) => !secrets.has(key) && env[key] !== undefined)
				.map((key) => [key, env[key]]),
		),
	};
}

function docker(args: string[], capture = false) {
	const result = spawnSync("docker", args, {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		stdio: capture ? ["ignore", "pipe", "inherit"] : "inherit",
	});
	if (result.error) {
		throw result.error;
	}
	return result;
}

async function checkScenarios(root: string) {
	const directory = await mkdtemp(path.resolve(tmpdir(), "k6-contracts-"));
	const container = [
		"run",
		"--rm",
		"--network=none",
		...dockerUser,
		"-v",
		`${root}/load-tests:/tests:ro`,
		"-v",
		`${directory}:/results`,
		k6Image,
	];
	const env = [
		"-e",
		"BASE_URL=http://example.test",
		"-e",
		"WEBHOOK_SECRET=test",
		"-e",
		"AUTH_TOKEN=test",
		"-e",
		"WORKSPACE_SLUG=test",
		"-e",
		"ARTIFACT_IDS=1,2",
	];
	try {
		for (const scenario of ["webhook-burst", "detection-mentor"]) {
			if (docker([...container, "inspect", ...env, `/tests/${scenario}.js`], true).status !== 0) {
				throw new Error(`Cannot inspect ${scenario}`);
			}
		}
		for (const [name, expected] of [
			["contracts", 0],
			["unfinished", 99],
		] as const) {
			const result = docker([
				...container,
				"run",
				...env,
				"-e",
				`TEST_CASE=${name}`,
				"/tests/scenarios.test.js",
			]);
			if (result.status !== expected) {
				throw new Error(`${name}: expected exit ${expected}, got ${result.status}`);
			}
			if (name !== "unfinished") {
				continue;
			}
			const summary = asRecord(
				parseJson(await readFile(path.resolve(directory, "summary.json"), "utf8")),
				"summary",
			);
			const metrics = asRecord(summary.metrics, "metrics");
			const metric = asRecord(metrics.review_jobs_finished, "review_jobs_finished");
			const verdicts = Object.values(asRecord(metric.thresholds, "thresholds"));
			if (
				verdicts.length === 0 ||
				verdicts.some((verdict) => asRecord(verdict, "threshold").ok !== false)
			) {
				throw new Error("Unfinished review threshold did not fail in the k6 summary");
			}
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

const cell = (value: string) => value.replaceAll("|", String.raw`\|`).replaceAll(/\r?\n/gu, " ");

function assertValidRun(run: Record<string, unknown>): void {
	if (!Number.isFinite(Date.parse(asString(run.startedAt, "startedAt")))) {
		throw new Error("Invalid run timestamp");
	}
	if (
		run.exitCode !== null &&
		(!Number.isInteger(run.exitCode) || Number(run.exitCode) < 0 || Number(run.exitCode) > 255)
	) {
		throw new Error("Invalid run exit code");
	}
}

/** Every configured threshold must have a verdict in the summary, and nothing else may. */
function assertThresholdEvidence(
	expected: Record<string, unknown>,
	metrics: Record<string, unknown>,
): void {
	for (const [name, expressions] of Object.entries(expected)) {
		const metric = asRecord(metrics[name], `metrics.${name}`);
		const actual = asRecord(metric.thresholds, `metrics.${name}.thresholds`);
		const thresholds = asStringArray(expressions, `options.thresholds.${name}`);
		if (
			thresholds.length === 0 ||
			thresholds.some((expression) => !(expression in actual)) ||
			Object.keys(actual).length !== thresholds.length
		) {
			throw new Error(`Missing or mismatched threshold evidence for ${name}`);
		}
	}
}

function assertRecordedInputs(config: Record<string, unknown>, recorded: readonly string[]): void {
	for (const [key, value] of Object.entries(config)) {
		if (![...inputs, ...recorded].includes(key) || secrets.has(key) || typeof value !== "string") {
			throw new Error(`Invalid recorded input ${key}`);
		}
	}
	for (const key of recorded) {
		if (
			typeof config[key] !== "string" ||
			!/^[1-9]\d*$/u.test(config[key]) ||
			!Number.isSafeInteger(Number(config[key]))
		) {
			throw new Error(`Missing gateway limit ${key}`);
		}
	}
}

function thresholdRows(
	metrics: Record<string, unknown>,
	expected: Record<string, unknown>,
): { rows: string[]; failed: boolean } {
	const rows: string[] = [];
	let failed = false;
	for (const [name, value] of Object.entries(metrics).toSorted(([a], [b]) => a.localeCompare(b))) {
		const metric = asRecord(value, name);
		if (metric.thresholds === undefined) {
			continue;
		}
		if (!(name in expected)) {
			throw new Error(`Unexpected threshold metric ${name}`);
		}
		for (const [expression, verdict] of Object.entries(asRecord(metric.thresholds, "thresholds"))) {
			const passed = asRecord(verdict, `metrics.${name}.thresholds.${expression}`).ok;
			if (typeof passed !== "boolean") {
				throw new TypeError("Expected a k6 threshold verdict");
			}
			failed ||= !passed;
			rows.push(`| ${cell(name)} | ${cell(expression)} | ${passed ? "PASS" : "FAIL"} |`);
		}
	}
	if (rows.length === 0) {
		throw new Error("Summary has no threshold evidence");
	}
	return { rows, failed };
}

function metricRows(metrics: Record<string, unknown>): string[] {
	return Object.entries(metrics)
		.toSorted(([a], [b]) => a.localeCompare(b))
		.flatMap(([name, value]) =>
			Object.entries(asRecord(asRecord(value, name).values, `metrics.${name}.values`)).map(
				([key, number]) => {
					if (typeof number !== "number" || !Number.isFinite(number)) {
						throw new Error(`Invalid metric ${name}.${key}`);
					}
					return `| ${cell(name)} | ${cell(key)} | ${number} |`;
				},
			),
		);
}

export function renderBaseline(summary: unknown, metadata: unknown, template: string) {
	const metrics = asRecord(asRecord(summary, "summary").metrics, "metrics");
	const run = asRecord(metadata, "run");
	const config = asRecord(run.inputs, "inputs");
	if (run.scenario !== "webhook-burst" && run.scenario !== "detection-mentor") {
		throw new Error("Invalid run scenario");
	}
	// A recorded run stays renderable after the checkout moves its pin, so the image only has to prove
	// it was digest-pinned; the document names the image the run actually used.
	const image = asString(run.image, "image");
	if (!/^grafana\/k6:[\w.-]+@sha256:[0-9a-f]{64}$/u.test(image)) {
		throw new Error("Run must record a digest-pinned k6 image");
	}
	const pinned = image === k6Image ? "" : ` (this checkout pins ${k6Image})`;
	assertValidRun(run);
	const options = asRecord(run.options, "options");
	const expected = asRecord(options.thresholds, "options.thresholds");
	const scenarios = asRecord(options.scenarios, "options.scenarios");
	if (Object.keys(expected).length === 0 || Object.keys(scenarios).length === 0) {
		throw new Error("Missing effective k6 options");
	}
	assertThresholdEvidence(expected, metrics);
	assertRecordedInputs(config, gatewayLimits(run.scenario));
	const { rows, failed: thresholdFailed } = thresholdRows(metrics, expected);
	const failed = run.exitCode !== 0 || thresholdFailed;
	const values = metricRows(metrics);
	const identity = Object.entries(config)
		.map(([key, value]) => `| ${cell(key)} | ${cell(String(value))} |`)
		.join("\n");
	// A function replacement is the only form that does not read `$&` and friends in a metric value
	// or a recorded input as a replacement pattern.
	return template
		.replace(
			"{{RUN}}",
			() =>
				`Scenario: ${cell(String(run.scenario))}\n\nStarted: ${cell(String(run.startedAt))}\n\nk6 image: ${cell(image)}${cell(pinned)}\n\nProcess exit: ${cell(String(run.exitCode))}\n\n**Automated result: ${failed ? "FAIL" : "PASS"}. Host qualification: PENDING operator evidence.**\n\n| Input (omitted values use scenario defaults) | Value |\n| --- | --- |\n${identity}`,
		)
		.replace("{{SCENARIOS}}", () => `\`\`\`json\n${JSON.stringify(scenarios, null, 2)}\n\`\`\``)
		.replace("| {{THRESHOLDS}} | | |", () => rows.join("\n"))
		.replace("| {{METRICS}} | | |", () => values.join("\n"));
}

async function main() {
	const [command, argument, ...extra] = process.argv.slice(2);
	if (extra.length > 0 || (command !== "report" && argument !== undefined)) {
		throw new Error("Unexpected load-test arguments");
	}
	const root = path.resolve(import.meta.dirname, "..");
	if (command === "syntax") {
		await checkScenarios(root);
		return;
	}
	if (command === "report") {
		if (!isSet(argument)) {
			throw new Error("Usage: report:load:baseline <run-directory>");
		}
		const directory = path.resolve(argument);
		const document = renderBaseline(
			parseJson(await readFile(path.resolve(directory, "summary.json"), "utf8")),
			parseJson(await readFile(path.resolve(directory, "run.json"), "utf8")),
			await readFile(path.resolve(directory, "baseline-template.md"), "utf8"),
		);
		await writeFile(path.resolve(directory, "baseline.md"), document);
		return;
	}
	const config = configuration(command ?? "", process.env);
	if (process.env.LOAD_TEST_ACKNOWLEDGE !== "isolated-host") {
		throw new Error("Set LOAD_TEST_ACKNOWLEDGE=isolated-host after reading load-tests/README.md");
	}
	const startedAt = new Date().toISOString();
	const directory = path.resolve(
		process.env.LOAD_RESULTS_DIR ??
			`load-results/${config.scenario}-${startedAt.replaceAll(":", "-")}`,
	);
	await mkdir(path.resolve(directory, ".."), { recursive: true });
	await mkdir(directory, { mode: 0o700 });
	for (const file of await Array.fromAsync(
		glob("**/*.js", { cwd: path.resolve(root, "load-tests") }),
	)) {
		const target = path.resolve(directory, "scripts", file);
		await mkdir(path.dirname(target), { recursive: true });
		await copyFile(path.resolve(root, "load-tests", file), target);
	}
	await copyFile(
		path.resolve(root, "load-tests/baseline-template.md"),
		path.resolve(directory, "baseline-template.md"),
	);
	const metadata = { ...config, startedAt, exitCode: null };
	await writeFile(path.resolve(directory, "run.json"), JSON.stringify(metadata, null, 2), {
		flag: "wx",
	});
	const args = [
		"run",
		"--rm",
		...dockerUser,
		...inputs.filter((key) => process.env[key] !== undefined).flatMap((key) => ["-e", key]),
		"-v",
		`${directory}/scripts:/tests:ro`,
		"-v",
		`${directory}:/results`,
		k6Image,
	];
	// `k6 inspect` defaults --include-system-env-vars to false where `k6 run` defaults it to true, so
	// the container environment carrying the workload — and the secrets kept out of argv — needs it.
	const inspected = docker(
		[...args, "inspect", "--include-system-env-vars", `/tests/${config.scenario}.js`],
		true,
	);
	if (inspected.status !== 0) {
		throw new Error("k6 inspection failed before load started");
	}
	const inspectedOptions = asRecord(parseJson(inspected.stdout), "k6 options");
	const options = {
		scenarios: asRecord(inspectedOptions.scenarios, "scenarios"),
		thresholds: asRecord(inspectedOptions.thresholds, "thresholds"),
	};
	await writeFile(
		path.resolve(directory, "run.json"),
		JSON.stringify({ ...metadata, options }, null, 2),
	);
	const result = docker([
		...args,
		"run",
		// The scenarios' handleSummary writes /results/summary.json; these are the statistics it carries.
		"--summary-trend-stats=avg,min,med,max,p(95),p(99)",
		`/tests/${config.scenario}.js`,
	]);
	const exitCode = result.status ?? 1;
	await writeFile(
		path.resolve(directory, "run.json"),
		JSON.stringify({ ...metadata, options, exitCode }, null, 2),
	);
	process.stdout.write(`Load evidence: ${directory}\n`);
	process.exitCode = exitCode;
}

if (import.meta.main) {
	await main();
}
