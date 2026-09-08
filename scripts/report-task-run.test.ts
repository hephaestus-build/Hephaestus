import assert from "node:assert/strict";
import { test } from "node:test";

import { unpassedTasks } from "./report-task-run.ts";

const report = `
Statistics:   4 tasks • 1 cache hits • 1 cache misses • 2 cache disabled • 2 failed
  [1] hephaestus#gate:webapp: $ vp -C webapp check ✓
      → Cache hit - output replayed - 8.16s saved
  ·······················································
  [2] hephaestus#gate:stories: $ node scripts/check-story-prose.ts ✗ (exit code: 1)
      → Cache miss: 'webapp/src/a.stories.tsx' modified
  ·······················································
  [3] hephaestus#gate:server-lint: $ node scripts/run-mvnw.ts -f application/pom.xml compile pmd:check -q ✗ (exit code: 137)
  ·······················································
  [4] hephaestus#gate:server-lint: $ node scripts/run-mvnw.ts -f application/pom.xml compile pmd:check -q ✗ (exit code: 1)
`;

void test("names each task that did not pass once, in report order", () => {
	assert.deepEqual(unpassedTasks(report), ["gate:stories", "gate:server-lint"]);
});

void test("a clean report names nothing", () => {
	assert.deepEqual(unpassedTasks("  [1] hephaestus#gate:webapp: $ vp -C webapp check ✓\n"), []);
});

void test("names a task the runner stopped, because the report does not distinguish it", () => {
	const stopped = `
  [1] hephaestus#gate:stories: $ node scripts/check-story-prose.ts ✗ (exit code: 1)
  [2] hephaestus#gate:runner-contract: $ node --test scripts/check-runner-contract.ts ✗ (exit code: 137)
`;
	assert.deepEqual(unpassedTasks(stopped), ["gate:stories", "gate:runner-contract"]);
});

void test("reads a coloured report, which would otherwise name nothing and exit clean", () => {
	const esc = "\u001B";
	const coloured =
		`  ${esc}[90m[1]${esc}[0m ${esc}[97;1mhephaestus#gate:stories${esc}[0m: ` +
		`${esc}[34m$ node scripts/check-story-prose.ts${esc}[0m ${esc}[31m\u2717${esc}[0m (exit code: 1)\n`;
	assert.deepEqual(unpassedTasks(coloured), ["gate:stories"]);
});
