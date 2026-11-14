import { glob, readFile } from "node:fs/promises";
import { availableParallelism } from "node:os";
import { fileURLToPath } from "node:url";

import { parse } from "yaml";

import { asArray, asRecord } from "./lib/json.ts";
import { run } from "./lib/process.ts";

const rulesFile = "hephaestus-alerts.yml";
const testsFile = "hephaestus-alerts.test.yml";
const runbookFile = "docs/admin/production-operations-runbook.mdx";

/** Where a notification sends its reader: the runbook section whose rows answer each alert. */
export const RUNBOOK_URL =
	"https://docs.hephaestus.build/admin/production-operations-runbook#alert-response";

/**
 * The alerts that are not complete: each alert needs a promtool case in which it fires, one in which
 * it stays silent, a row in the runbook's alert response section, and a `runbook_url` annotation that
 * links that section. promtool itself passes a rule without any test.
 */
export function incompleteAlerts(rules: string, tests: string, runbook: string): string[] {
	const alertRules = asArray(asRecord(parse(rules), rulesFile).groups, "groups")
		.flatMap((group) => asArray(asRecord(group, "group").rules, "rules"))
		.map((rule) => asRecord(rule, "rule"))
		.filter((rule) => typeof rule.alert === "string");
	const alerts = new Set(alertRules.map((rule) => String(rule.alert)));
	const linked = new Set(
		alertRules
			.filter((rule) => asRecord(rule.annotations ?? {}, "annotations").runbook_url === RUNBOOK_URL)
			.map((rule) => String(rule.alert)),
	);
	const cases = asArray(asRecord(parse(tests), testsFile).tests, "tests")
		.flatMap((test) => asArray(asRecord(test, "test").alert_rule_test ?? [], "alert_rule_test"))
		.map((rule) => asRecord(rule, "alert_rule_test"));
	const expecting = (fires: boolean) =>
		new Set(
			cases
				.filter((rule) => asArray(rule.exp_alerts ?? [], "exp_alerts").length > 0 === fires)
				.map((rule) => rule.alertname),
		);
	const [firing, silent] = [expecting(true), expecting(false)];
	const section =
		/^## Alert response$(?<rows>[\s\S]*?)(?=^## |(?![\s\S]))/mu.exec(runbook)?.groups?.rows ?? "";
	// The Signal column of each table row names the alerts that the row answers.
	const answered = new Set(
		section
			.replaceAll(/<!--[\s\S]*?-->/gu, "")
			.split("\n")
			.filter((line) => line.startsWith("|"))
			.flatMap((row) => row.split("|")[1]?.match(/`Hephaestus\w+`/gu) ?? [])
			.map((name) => name.slice(1, -1)),
	);
	return [...alerts].flatMap((alert) => [
		...(firing.has(alert) ? [] : [`${alert}: no promtool case in ${testsFile} fires it`]),
		...(silent.has(alert) ? [] : [`${alert}: no promtool case in ${testsFile} keeps it silent`]),
		...(answered.has(alert) ? [] : [`${alert}: no row in ${runbookFile} § Alert response`]),
		...(linked.has(alert) ? [] : [`${alert}: no runbook_url annotation ${RUNBOOK_URL}`]),
	]);
}

/** The pages whose tables give the reason for each alert's threshold: one row per alert, on one of them. */
export const ALERT_REASON_PAGES = [
	"docs/admin/alert-rules.md",
	"docs/admin/observability.mdx",
	"docs/admin/monitoring.md",
];

/** The alerts that no table row of the reason pages names. A row in the runbook says what to do, not why. */
export function unexplainedAlerts(rules: string, pages: readonly string[]): string[] {
	const named = new Set(
		pages.flatMap((page) =>
			page
				.split("\n")
				.filter((line) => line.startsWith("|"))
				.flatMap((row) => row.match(/`Hephaestus\w+`/gu) ?? [])
				.map((name) => name.slice(1, -1)),
		),
	);
	return asArray(asRecord(parse(rules), rulesFile).groups, "groups")
		.flatMap((group) => asArray(asRecord(group, "group").rules, "rules"))
		.map((rule) => asRecord(rule, "rule").alert)
		.filter((alert): alert is string => typeof alert === "string" && !named.has(alert))
		.map(
			(alert) => `${alert}: no table row in ${ALERT_REASON_PAGES.join(", ")} gives its threshold`,
		);
}

/**
 * The counters of samplers that keep their gauges' last values when a pass fails. While a sampler fails, an alert on
 * those gauges reads an old value and stays silent, so each such counter needs an alert of its own.
 */
export function unwatchedSamplers(rules: string, javaSources: readonly string[]): string[] {
	const expressions = asArray(asRecord(parse(rules), rulesFile).groups, "groups")
		.flatMap((group) => asArray(asRecord(group, "group").rules, "rules"))
		.map((rule) => asRecord(rule, "rule"))
		.filter((rule) => typeof rule.alert === "string")
		.map((rule) => String(rule.expr));
	const counters = new Set(
		javaSources.flatMap((source) =>
			[...source.matchAll(/"(?<meter>[a-z0-9_.]+\.sampler\.failures?)"/gu)].map(
				(match) => `${(match.groups?.meter ?? "").replaceAll(".", "_")}_total`,
			),
		),
	);
	return [...counters]
		.filter(
			(counter) =>
				!expressions.some((expression) => new RegExp(`\\b${counter}\\b`, "u").test(expression)),
		)
		.map((counter) => `${counter}: no alert in ${rulesFile} reads this sampler failure counter`);
}

/**
 * The test groups split round-robin into `parts` lists of exact `--run` patterns. promtool runs one
 * group at a time on one core, which took 7 of the tooling leg's 9 minutes, so each list gets a
 * promtool process of its own.
 */
export function runPatterns(tests: string, parts: number): string[][] {
	const names = asArray(asRecord(parse(tests), testsFile).tests, "tests").map((test) => {
		const { name } = asRecord(test, "test");
		if (typeof name !== "string" || name === "") {
			throw new Error(`${testsFile}: every test group needs a name`);
		}
		return `--run=^${name.replaceAll(/[\\^$.*+?()[\]{}|]/gu, String.raw`\$&`)}$`;
	});
	const count = Math.max(1, Math.min(parts, names.length));
	return Array.from({ length: count }, (_unused, part) =>
		names.filter((_name, index) => index % count === part),
	);
}

const read = async (path: string) => readFile(new URL(`../${path}`, import.meta.url), "utf8");

/** The application's main Java sources, which declare the meter names. */
export async function readJavaSources(): Promise<string[]> {
	const files = await Array.fromAsync(
		glob("server/application/src/main/java/**/*.java", {
			cwd: fileURLToPath(new URL("..", import.meta.url)),
		}),
	);
	return Promise.all(files.map(async (file) => read(file)));
}

if (import.meta.main) {
	const rulesText = await read(`deploy/chart/hephaestus/files/${rulesFile}`);
	const javaSources = await readJavaSources();
	const gaps = [
		...incompleteAlerts(
			rulesText,
			await read(`deploy/chart/hephaestus/files/${testsFile}`),
			await read(runbookFile),
		),
		...unwatchedSamplers(rulesText, javaSources),
		...unexplainedAlerts(rulesText, await Promise.all(ALERT_REASON_PAGES.map(read))),
	];
	if (gaps.length > 0) {
		console.error(gaps.join("\n"));
		process.exit(1);
	}

	// promtool from the Prometheus release image, pinned by its index digest: one pin for CI, Linux and
	// macOS, and no binary to install.
	const prometheusImage =
		"prom/prometheus:v3.15.1-distroless@sha256:451a845bae1d51622b41af1780eb2ad911bbb79a9855bc8e9bb67fabbc03f934";
	const rules = fileURLToPath(new URL("../deploy/chart/hephaestus/files", import.meta.url));
	const promtool = [
		"run",
		"--rm",
		"--network=none",
		"-v",
		`${rules}:/rules:ro`,
		"--entrypoint",
		"promtool",
		prometheusImage,
	];

	await run("docker", [...promtool, "check", "rules", `/rules/${rulesFile}`]);
	const tests = await read(`deploy/chart/hephaestus/files/${testsFile}`);
	await Promise.all(
		runPatterns(tests, availableParallelism()).map(async (patterns) =>
			run("docker", [...promtool, "test", "rules", ...patterns, `/rules/${testsFile}`]),
		),
	);
}
