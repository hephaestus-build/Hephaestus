import assert from "node:assert/strict";
import { test } from "node:test";

import { confirmationFindings } from "./check-adr-confirmation.ts";
import { baselineIds, ratchetProblems } from "./lib/debt-ratchet.ts";

const file = "docs/decisions/1234-example.md";
const files = new Set([
	"server/application/src/test/java/example/BoundaryTest.java",
	"scripts/example.test.ts",
	"scripts/check-example.ts",
]);
const tasks = new Set(["gate:example"]);
const check = (section: string, status = "Accepted") =>
	confirmationFindings(
		file,
		`# ADR 1234: Example\n\n**Status:** ${status}\n\n${section}`,
		files,
		tasks,
	);

void test("an Accepted ADR needs a Confirmation with an existing test, task or script", () => {
	assert.deepEqual(check("## Decision\nDo it."), [`${file}: missing Confirmation section`]);
	for (const reference of [
		"BoundaryTest",
		"BoundaryTest#checksBoundary",
		"gate:example",
		"vp run gate:example",
		"scripts/check-example.ts",
		"example.test.ts",
	]) {
		assert.deepEqual(check(`## Confirmation\n\n\`${reference}\` checks the rule.`), [], reference);
	}
	assert.deepEqual(check("## Confirmation\nReview catches this."), [
		`${file}: Confirmation names no existing executable check`,
	]);
});

void test("every named check resolves even if another check exists", () => {
	assert.deepEqual(check("## Confirmation\n`BoundaryTest` and `MissingTest`."), [
		`${file}: missing check MissingTest`,
	]);
	assert.deepEqual(check("## Confirmation\n`gate:example` and `scripts/missing.ts`."), [
		`${file}: missing check scripts/missing.ts`,
	]);
});

void test("code fences, other sections, non-test classes and retired ADRs do not confirm a decision", () => {
	assert.deepEqual(
		check("## Confirmation\n```md\n`BoundaryTest`\n```\n## Sources\n`BoundaryTest`"),
		[`${file}: Confirmation names no existing executable check`],
	);
	assert.deepEqual(check("## Confirmation\n`BoundaryTest`", "Proposed"), []);
	assert.deepEqual(
		check("## Confirmation\n`BoundaryTest`", "Superseded by [ADR 5678](5678-new-decision.md)"),
		[],
	);
	assert.deepEqual(
		confirmationFindings(
			file,
			"**Status:** Accepted\n\n## Confirmation\n`BoundaryTest`",
			new Set(["server/application/src/main/java/BoundaryTest.java"]),
			tasks,
		),
		[
			`${file}: Confirmation names no existing executable check`,
			`${file}: missing check BoundaryTest`,
		].toSorted(),
	);
});

void test("wildcard test references need a matching test file", () => {
	assert.deepEqual(check("## Confirmation\n`*BoundaryTest`"), []);
	assert.equal(check("## Confirmation\n`*AbsentTest`").length, 2);
});

void test("debt cannot grow, move to a new identity, or retain a stale exception", () => {
	assert.deepEqual(ratchetProblems(["a"], ["a"], ["a", "b"]), []);
	assert.match(ratchetProblems(["b"], ["b"], ["a"]).join("\n"), /baseline growth/u);
	assert.match(ratchetProblems(["b"], ["a"], ["a"]).join("\n"), /fix new debt/u);
	assert.match(ratchetProblems([], ["a"], ["a"]).join("\n"), /Delete its baseline entry/u);
	assert.deepEqual(ratchetProblems(["a"], ["a"], undefined), []);
});

void test("baseline entries require ownership and a removal condition", () => {
	const entry = { id: "a", owner: "GUARDS", reason: "No check", removeWhen: "Add check" };
	assert.deepEqual(baselineIds(JSON.stringify([entry]), "baseline.json"), ["a"]);
	assert.throws(
		() => baselineIds(JSON.stringify([{ ...entry, owner: "" }]), "baseline.json"),
		/owner/u,
	);
	assert.throws(() => baselineIds(JSON.stringify([entry, entry]), "baseline.json"), /duplicate/u);
	assert.throws(() => baselineIds("{}", "baseline.json"), /array/u);
});

void test("malformed or absent status cannot bypass confirmation", () => {
	for (const status of ["Accepted.", "Accepted (2026-11)", "Unknown"]) {
		assert.match(check("", status).join("\n"), /use an ADR status/u);
	}
	assert.match(
		confirmationFindings(file, "# ADR 1234: Example", files, tasks).join("\n"),
		/use an ADR status/u,
	);
});
