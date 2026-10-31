import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { incompleteAlerts } from "./check-alert-rules.ts";

const read = async (path: string) => readFile(new URL(`../${path}`, import.meta.url), "utf8");

const rules = `
groups:
  - name: hephaestus
    rules:
      - record: hephaestus:up
        expr: up
      - alert: HephaestusAnswered
        expr: up == 0
      - alert: HephaestusUntested
        expr: up == 0
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

await test("every shipped alert fires in a promtool case and has a runbook row", async () => {
	assert.deepEqual(
		incompleteAlerts(
			await read("deploy/observability/hephaestus-alerts.yml"),
			await read("deploy/observability/hephaestus-alerts.test.yml"),
			await read("docs/admin/production-operations-runbook.mdx"),
		),
		[],
	);
});
