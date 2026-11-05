import { readFile } from "node:fs/promises";
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

const read = async (path: string) => readFile(new URL(`../${path}`, import.meta.url), "utf8");

if (import.meta.main) {
	const gaps = incompleteAlerts(
		await read(`deploy/chart/hephaestus/files/${rulesFile}`),
		await read(`deploy/chart/hephaestus/files/${testsFile}`),
		await read(runbookFile),
	);
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
	await run("docker", [...promtool, "test", "rules", `/rules/${testsFile}`]);
}
