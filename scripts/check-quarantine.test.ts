import assert from "node:assert/strict";
import { test } from "node:test";

import { isTestSource, quarantineFindings } from "./check-quarantine.ts";

const today = new Date("2026-10-24T15:00:00Z");
const missing = "skips a test without `quarantine #<issue> @<owner> until <YYYY-MM-DD>`";
const lines = (file: string, source: string) =>
	quarantineFindings(file, source, today).map(({ line, problem }) => ({ line, problem }));

void test("every unconditional skip shape without a marker fails, wherever the formatter breaks it", () => {
	const shapes = [
		'it.skip("loads", () => {});',
		'it.skip(\n\t"a name the formatter wrapped",\n\tasync () => {},\n);',
		'it.skip.each([1, 2])("loads %s", () => {});',
		"test.fixme();",
		'test.fixme("loads", async () => {});',
		'test.describe.skip("suite", () => {});',
		'describe.skip("suite", () => {});',
		'it.todo("later");',
		"xit('loads', () => {});",
		"it.skip(`a template name`, () => {});",
		'test.skip(\n\t// why\n\t"loads",\n\t() => {},\n);',
		"it.skip(name, () => {});",
		"describe.skip(SUITE, () => {});",
		"test.skip(title, async () => {});",
		'test.skip(true, "always");',
		'it.concurrent.skip("loads", () => {});',
		'describe.sequential.skip("suite", () => {});',
		'suite.skip("suite", () => {});',
		'bench.skip("sorts", () => {});',
		'test.skip.each([1])("loads %s", () => {});',
	];
	for (const shape of shapes) {
		assert.deepEqual(lines("src/a.test.ts", shape), [{ line: 1, problem: missing }], shape);
	}
	assert.deepEqual(lines("A.java", "@Disabled\nvoid loads() {}\n"), [
		{ line: 1, problem: missing },
	]);
	assert.deepEqual(lines("A.java", '@Disabled("flaky (see #12)")\nvoid loads() {}\n'), [
		{ line: 1, problem: missing },
	]);
	assert.deepEqual(lines("A.java", '@Disabled(\n\t"flaky")\nvoid loads() {}\n'), [
		{ line: 1, problem: missing },
	]);
});

void test("a quarantine names its issue, its owner and an end date within 14 days", () => {
	const marker = "quarantine #3150 @octo-cat until 2026-11-07: free-port race";
	assert.deepEqual(lines("A.java", `@Disabled("${marker}")\n`), []);
	assert.deepEqual(lines("A.java", `@Disabled(\n\t\t"${marker}")\n`), []);
	assert.deepEqual(lines("A.java", `@Disabled(\n\t\t"flaky (port) ${marker}")\n`), []);
	assert.deepEqual(lines("src/a.test.ts", `// ${marker}\nit.skip("loads", () => {});\n`), []);
	assert.deepEqual(
		lines("src/a.test.ts", `// ${marker}\nit.skip(\n\t"wrapped",\n\t() => {},\n);\n`),
		[],
	);
	assert.deepEqual(lines("src/a.test.ts", `// ${marker}\ntest.fixme();\n`), []);
});

void test("a quarantine without an owner fails", () => {
	assert.deepEqual(
		lines("A.java", '@Disabled("quarantine #3150 until 2026-11-07: free-port race")\n'),
		[{ line: 1, problem: missing }],
	);
});

void test("an expired or distant quarantine fails", () => {
	assert.deepEqual(lines("A.java", '@Disabled("quarantine #12 @owner until 2026-10-23: race")\n'), [
		{ line: 1, problem: "quarantine of #12 expired on 2026-10-23" },
	]);
	assert.deepEqual(lines("A.java", '@Disabled("quarantine #12 @owner until 2026-11-08: race")\n'), [
		{ line: 1, problem: "quarantine of #12 runs past 14 days (2026-11-08)" },
	]);
});

void test("a quarantine whose end date is not a calendar day fails", () => {
	for (const end of ["2027-02-30", "2026-11-31"]) {
		assert.deepEqual(lines("A.java", `@Disabled("quarantine #12 @owner until ${end}: race")\n`), [
			{ line: 1, problem: `quarantine of #12 names no calendar date (${end})` },
		]);
	}
	const leapDay = '@Disabled("quarantine #12 @owner until 2028-02-29: race")\n';
	assert.deepEqual(quarantineFindings("A.java", leapDay, new Date("2028-02-20T12:00:00Z")), []);
});

void test("an environment-gated skip is not a quarantine", () => {
	const source = [
		'test.skip(!process.env.HEPHAESTUS_LIVE, "needs live credentials");',
		"test.skip(\n\tisCI,\n\t`needs a display`,\n);",
		'it.skipIf(!hasDocker)("runs a container", () => {});',
		'test.fixme(browserName === "webkit", "upstream bug");',
	].join("\n");
	assert.deepEqual(lines("e2e/live.spec.ts", source), []);
	assert.deepEqual(
		lines("A.java", '@DisabledIfEnvironmentVariable(named = "CI", matches = "true")\n'),
		[],
	);
	assert.deepEqual(lines("A.java", "@DisabledOnOs(OS.WINDOWS)\n"), []);
});

void test("only test sources are read", () => {
	assert.equal(isTestSource("server/application/src/test/java/de/A.java"), true);
	assert.equal(isTestSource("server/application/src/main/java/de/A.java"), false);
	assert.equal(isTestSource("webapp/src/routes/-a-route.test.tsx"), true);
	assert.equal(isTestSource("webapp/e2e/a.spec.ts"), true);
	assert.equal(isTestSource("webapp/src/a.ts"), false);
	assert.equal(isTestSource("scripts/check-quarantine.test.ts"), false);
});
