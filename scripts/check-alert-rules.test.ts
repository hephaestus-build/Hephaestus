import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import {
	incompleteAlerts,
	readJavaSources,
	RUNBOOK_URL,
	runPatterns,
	unwatchedSamplers,
} from "./check-alert-rules.ts";

const read = async (path: string) => readFile(new URL(`../${path}`, import.meta.url), "utf8");

const rules = `
groups:
  - name: hephaestus
    rules:
      - record: hephaestus:up
        expr: up
      - alert: HephaestusAnswered
        expr: up == 0
        annotations:
          runbook_url: ${RUNBOOK_URL}
      - alert: HephaestusUntested
        expr: up == 0
        annotations:
          runbook_url: ${RUNBOOK_URL}
`;
const tests = `
tests:
  - alert_rule_test:
      - alertname: HephaestusAnswered
        exp_alerts: []
      - alertname: HephaestusAnswered
        exp_alerts:
          - exp_labels: { severity: warning }
      - alertname: HephaestusUntested
        exp_alerts: []
`;
const runbook = `
## Alert response

| Signal | Contain and diagnose |
| --- | --- |
| Down (\`HephaestusAnswered\`, \`HephaestusUntested\`) | Restart it |

## Later

\`HephaestusElsewhere\`
`;

await test("an alert needs a case that fires it and a case that keeps it silent", () => {
	const onlyFiring = tests.replace(
		"      - alertname: HephaestusAnswered\n        exp_alerts: []\n",
		"",
	);
	assert.deepEqual(incompleteAlerts(rules, onlyFiring, runbook), [
		"HephaestusAnswered: no promtool case in hephaestus-alerts.test.yml keeps it silent",
		"HephaestusUntested: no promtool case in hephaestus-alerts.test.yml fires it",
	]);
});

await test("an alert named only outside the alert response section has no runbook row", () => {
	const elsewhere = rules.replace("HephaestusUntested", "HephaestusElsewhere");
	const firingTests = `${tests}      - alertname: HephaestusElsewhere\n        exp_alerts: []\n      - alertname: HephaestusElsewhere\n        exp_alerts:\n          - exp_labels: {}\n`;
	assert.deepEqual(incompleteAlerts(elsewhere, firingTests, runbook), [
		"HephaestusElsewhere: no row in docs/admin/production-operations-runbook.mdx § Alert response",
	]);
});

await test("a name in a comment, in prose or in a later column is no runbook row", () => {
	const mentioned = `
## Alert response

<!-- \`HephaestusUntested\` -->
Check \`HephaestusUntested\` later.

| Signal | Contain and diagnose |
| --- | --- |
| Down (\`HephaestusAnswered\`) | Compare with \`HephaestusUntested\` |
`;
	const firingTests = `${tests}      - alertname: HephaestusUntested\n        exp_alerts:\n          - exp_labels: {}\n`;
	assert.deepEqual(incompleteAlerts(rules, firingTests, mentioned), [
		"HephaestusUntested: no row in docs/admin/production-operations-runbook.mdx § Alert response",
	]);
});

await test("an alert without the runbook link sends no reader to its row", () => {
	const unlinked = rules.replace(`          runbook_url: ${RUNBOOK_URL}\n`, "");
	const firingTests = `${tests}      - alertname: HephaestusUntested\n        exp_alerts:\n          - exp_labels: {}\n`;
	assert.deepEqual(incompleteAlerts(unlinked, firingTests, runbook), [
		`HephaestusAnswered: no runbook_url annotation ${RUNBOOK_URL}`,
	]);
});

await test("every shipped alert fires in a promtool case and has a runbook row", async () => {
	assert.deepEqual(
		incompleteAlerts(
			await read("deploy/chart/hephaestus/files/hephaestus-alerts.yml"),
			await read("deploy/chart/hephaestus/files/hephaestus-alerts.test.yml"),
			await read("docs/admin/production-operations-runbook.mdx"),
		),
		[],
	);
});

void test("the promtool groups split into parts that select each group exactly once, by its whole name", async () => {
	const groups = await read("deploy/chart/hephaestus/files/hephaestus-alerts.test.yml");
	const parts = runPatterns(groups, 4);
	assert.equal(parts.length, 4);
	const patterns = parts.flat().map((flag) => new RegExp(flag.replace(/^--run=/u, ""), "u"));
	const names = [...groups.matchAll(/^ {2}- name: (?<name>.+)$/gmu)].map(
		(match) => match.groups?.name ?? "",
	);
	assert.equal(patterns.length, names.length);
	for (const name of names) {
		assert.equal(patterns.filter((pattern) => pattern.test(name)).length, 1, name);
	}
	const special = runPatterns("tests:\n  - name: a (b) | c.d\n", 8);
	assert.deepEqual(special, [[String.raw`--run=^a \(b\) \| c\.d$`]]);
	assert.throws(() => runPatterns("tests:\n  - interval: 1m\n", 2));
});

const watching = (expr: string) =>
	`groups:\n  - name: h\n    rules:\n      - alert: HephaestusQueueUnsampled\n        expr: ${expr}\n`;

void test("a sampler failure counter without an alert is refused", () => {
	const sources = [
		'String WATCHED = "queue.sampler.failures";',
		'Counter.builder("runs.sampler.failure").register(meters);',
	];
	assert.deepEqual(
		unwatchedSamplers(watching("increase(queue_sampler_failures_total[15m]) > 10"), sources),
		[
			"runs_sampler_failure_total: no alert in hephaestus-alerts.yml reads this sampler failure counter",
		],
	);
	assert.deepEqual(
		unwatchedSamplers(
			watching(
				"increase(queue_sampler_failures_total[15m]) + increase(runs_sampler_failure_total[15m]) > 10",
			),
			sources,
		),
		[],
	);
});

void test("every sampler failure counter on main has an alert", async () => {
	const sources = await readJavaSources();
	assert.deepEqual(
		unwatchedSamplers(await read("deploy/chart/hephaestus/files/hephaestus-alerts.yml"), sources),
		[],
	);
});
