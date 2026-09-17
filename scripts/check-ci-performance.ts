import { appendFile, readdir, readFile } from "node:fs/promises";
import path from "node:path";

import { isRecord } from "./lib/json.ts";
import { median } from "./lib/statistics.ts";
import { type TestSummary, validateProfile } from "./summarize-test-results.ts";

interface Metric {
	name: string;
	unit: string;
	value: (summary: TestSummary) => number;
	tolerance: number;
}

const metrics: Metric[] = [
	{
		name: "wall time",
		unit: "s",
		value: (summary) => summary.performance?.wallTimeSeconds ?? 0,
		tolerance: 0.2,
	},
	{
		name: "summed test time",
		unit: "s",
		value: (summary) => summary.testTimeSeconds,
		tolerance: 0.15,
	},
];

const percentile = (values: number[], percentage: number): number => {
	const sorted = values.toSorted((left, right) => left - right);
	return sorted[Math.ceil(sorted.length * percentage) - 1] ?? 0;
};

function historyMarkdown(summaries: TestSummary[]): string {
	const usable = summaries.filter((summary) => summary.performance !== undefined).slice(-8);
	const rows = [
		...metrics.map((metric) => [metric.name, metric.unit, usable.map(metric.value)] as const),
		[
			"context starts",
			"count",
			usable.map((summary) => summary.performance?.contextStarts ?? 0),
		] as const,
		[
			"context startup time",
			"s",
			usable.map((summary) => summary.performance?.contextStartupSeconds ?? 0),
		] as const,
		[
			"context cache misses",
			"count",
			usable.map((summary) => summary.performance?.contextCacheMisses ?? 0),
		] as const,
	];
	return `${[
		"## Server integration profile baseline",
		"",
		`Profiles: **${usable.length}/8**`,
		"",
		"| Metric | Unit | p50 | p95 |",
		"|---|---|---:|---:|",
		...rows.map(
			([name, unit, values]) =>
				`| ${name} | ${unit} | ${percentile(values, 0.5).toFixed(1)} | ${percentile(values, 0.95).toFixed(1)} |`,
		),
	].join("\n")}\n`;
}

export function regressions(current: TestSummary, history: TestSummary[]): string[] {
	validateProfile(current);
	for (const previous of history) {
		validateProfile(previous);
	}
	const failures: string[] = [];
	const usable = history.filter((summary) => summary.performance !== undefined).slice(-7);
	if (usable.length < 7) {
		return failures;
	}
	const baselineRuns = usable.slice(0, 5);
	const candidates = [...usable.slice(5), current];
	if (candidates.every((summary) => (summary.performance?.contextStarts ?? 0) > 15)) {
		failures.push("context starts exceeded 15 in three consecutive profiles");
	}
	if (candidates.every((summary) => (summary.performance?.contextStartupSeconds ?? 0) > 120)) {
		failures.push("context startup exceeded 120s in three consecutive profiles");
	}
	for (const metric of metrics) {
		const baselineValues = baselineRuns.map(metric.value);
		const baseline = median(baselineValues) ?? 0;
		const deviation = median(baselineValues.map((value) => Math.abs(value - baseline))) ?? 0;
		const limit = baseline + Math.max(baseline * metric.tolerance, deviation * 3);
		if (candidates.every((summary) => metric.value(summary) > limit)) {
			failures.push(`${metric.name} exceeded variance limit ${limit.toFixed(1)} three times`);
		}
	}
	return failures;
}

function metricField(record: Record<string, unknown>, key: string): number {
	const candidate = record[key];
	if (typeof candidate !== "number" || !Number.isFinite(candidate) || candidate < 0) {
		throw new Error(`Invalid CI metrics field: ${key}`);
	}
	return candidate;
}

export function parseSummary(json: string): TestSummary {
	const value: unknown = JSON.parse(json);
	if (
		!isRecord(value) ||
		value.schemaVersion !== 3 ||
		typeof value.name !== "string" ||
		!isRecord(value.performance)
	) {
		throw new Error("Invalid CI metrics summary");
	}
	const summary: TestSummary = {
		schemaVersion: 3,
		name: value.name,
		files: metricField(value, "files"),
		tests: metricField(value, "tests"),
		failures: metricField(value, "failures"),
		errors: metricField(value, "errors"),
		skipped: metricField(value, "skipped"),
		testTimeSeconds: metricField(value, "testTimeSeconds"),
		slowest: [],
		performance: {
			wallTimeSeconds: metricField(value.performance, "wallTimeSeconds"),
			contextStarts: metricField(value.performance, "contextStarts"),
			contextStartupSeconds: metricField(value.performance, "contextStartupSeconds"),
			contextCacheMisses: metricField(value.performance, "contextCacheMisses"),
		},
	};
	validateProfile(summary);
	return summary;
}

function verdict(profiles: number, failures: number): string {
	if (profiles < 7) {
		return "insufficient-data";
	}
	return failures > 0 ? "regression" : "within-budget";
}

async function main(): Promise<void> {
	const [currentPath, historyDirectory] = process.argv.slice(2);
	if (currentPath === undefined || historyDirectory === undefined) {
		throw new Error("Usage: check-ci-performance <current-json> <history-directory>");
	}
	const current = parseSummary(await readFile(currentPath, "utf8"));
	let files: string[] = [];
	try {
		const listed = await readdir(historyDirectory);
		files = listed
			.filter((file) => file.endsWith(".json"))
			.toSorted((left, right) => left.localeCompare(right, undefined, { numeric: true }));
	} catch (error) {
		if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) {
			throw error;
		}
	}
	const history = await Promise.all(
		files.map(async (file) =>
			parseSummary(await readFile(path.resolve(historyDirectory, file), "utf8")),
		),
	);
	const failures = regressions(current, history);
	const status = verdict(history.length, failures.length);
	const rendered = `${historyMarkdown([...history, current])}\nStatus: **${status}** (advisory).\n\n${failures.map((failure) => `- ${failure}\n`).join("")}\nCompare retained JFR and Gradle profiles before attributing a change to code; shared-runner variation and suite growth can affect these measurements.\n`;
	process.stdout.write(rendered);
	if (process.env.GITHUB_STEP_SUMMARY !== undefined) {
		await appendFile(process.env.GITHUB_STEP_SUMMARY, rendered);
	}
	for (const failure of failures) {
		process.stdout.write(`::warning title=Integration profile regression::${failure}\n`);
	}
	if (status === "insufficient-data") {
		process.stdout.write(
			"::notice title=Integration profile baseline incomplete::Eight valid profiles are needed for a sustained-regression verdict.\n",
		);
	}
}

if (import.meta.main) {
	await main();
}
