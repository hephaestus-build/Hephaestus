import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	assertShrinks,
	baselinePath,
	check,
	parseBaseline,
	reconcile,
	scanSource,
	type Allowance,
} from "./check-band-aids.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { parseJson } from "./lib/json.ts";

const directive =
	"// oxlint-disable-next-line no-debugger -- The external debugger protocol requires this statement.";
const allowance: Allowance = {
	file: "src/protocol.ts",
	category: "lint-suppression",
	source: directive.split(" -- ")[0] ?? "",
	context: "<module> -> <end>",
	count: 1,
	owner: "BANDAID",
	reason: "constraint: The external debugger protocol requires this statement.",
};

await test("counts active controls while preserving fixture strings and regular expressions", () => {
	const source = [
		'const quoted = "it.skip(\\\"fixture\\\")";',
		"const template = `// oxlint-disable-next-line fixture\nit.skip('fixture')`;",
		"const expression = /['\"]/;",
		directive,
		'it.skip("real disabled test", () => {});',
	].join("\n");
	const findings = scanSource("src/protocol.test.ts", source);
	assert.deepEqual(
		findings.map(({ category, line }) => ({ category, line })),
		[
			{ category: "lint-suppression", line: 5 },
			{ category: "test-skip", line: 6 },
		],
	);
});

await test("a wrapped annotation retains every warning name in its identity", () => {
	const findings = scanSource(
		"src/Example.java",
		'@java.lang.SuppressWarnings(\n {"unchecked", "rawtypes"}\n)\nclass Example {}',
	);
	assert.equal(findings.length, 1);
	const [finding] = findings;
	assert.ok(finding);
	assert.equal(finding.source, '@java.lang.SuppressWarnings( {"unchecked", "rawtypes"} )');
	assert.equal(finding.line, 1);
});

await test("counts swallowed failures across comments and records no rethrown catch", () => {
	const findings = scanSource(
		"src/protocol.ts",
		[
			"try { read(); } catch (error) {",
			"  // Optional browser preference.",
			"}",
			"try { write(); } catch (error) { throw error; }",
			"void close().catch(() => undefined);",
		].join("\n"),
	);
	assert.equal(findings.filter(({ category }) => category === "ignored-catch").length, 2);
});

await test("generated copies have no owned-source allowance", () => {
	for (const file of [
		"webapp/src/api/client.gen.ts",
		"extension/src/api/client.gen.ts",
		"webapp/src/routeTree.gen.ts",
		"webapp/.storybook/public/mockServiceWorker.js",
	]) {
		assert.deepEqual(scanSource(file, directive), []);
	}
});

await test("a diagnostic retry counts, while a disabled QueryClient retry does not", () => {
	assert.deepEqual(scanSource("src/protocol.test.ts", "const options = { retry: false };"), []);
	assert.equal(
		scanSource("webapp/playwright.config.ts", "export default { retries: onCI ? 1 : 0 };")[0]
			?.category,
		"test-retry",
	);
});

await test("malformed, duplicate and unexplained allowances fail closed", () => {
	for (const value of [
		[{ ...allowance, reason: "temporary" }],
		[{ ...allowance, reason: "constraint: " }],
		[{ ...allowance, owner: "" }],
		[{ ...allowance, category: "unknown" }],
		[{ ...allowance, count: 0 }],
		[{ ...allowance, file: "../outside.ts" }],
		[allowance, allowance],
	]) {
		assert.throws(() => parseBaseline(JSON.stringify(value)));
	}
});

await test("the baseline rejects relocation and multiplicity growth while permitting owner corrections", () => {
	assert.throws(
		() => assertShrinks([allowance], [{ ...allowance, file: "src/moved.ts" }]),
		/Baseline grew/u,
	);
	assert.throws(() => assertShrinks([allowance], [{ ...allowance, count: 2 }]), /Baseline grew/u);
	assert.doesNotThrow(() => assertShrinks([allowance], [{ ...allowance, owner: "PROTOCOL" }]));
	assert.doesNotThrow(() => assertShrinks([allowance], []));
});

await test("shrink removes stale capacity and cannot admit a new occurrence", () => {
	assert.throws(() => reconcile([], [allowance], false), /Stale baseline/u);
	assert.deepEqual(reconcile([], [allowance], true), []);
	assert.throws(
		() => reconcile(scanSource(allowance.file, `${directive}\n${directive}`), [allowance], true),
		/New lint-suppression/u,
	);
});

await test("the real checkout scan rejects a forged baseline and includes untracked source", (t) => {
	const directory = mkdtempSync(path.join(tmpdir(), "band-aid-gate-"));
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	const git = (...args: string[]) =>
		execFileSync("git", args, {
			cwd: directory,
			env: environmentForGitFixture(),
			encoding: "utf8",
		});
	git("init", "--initial-branch=main");
	git("config", "user.name", "Gate fixture");
	git("config", "user.email", "fixture@example.invalid");
	mkdirSync(path.join(directory, "src"));
	mkdirSync(path.join(directory, "tools/band-aids"), { recursive: true });
	writeFileSync(path.join(directory, allowance.file), `${directive}\n`);
	writeFileSync(path.join(directory, baselinePath), JSON.stringify([allowance]));
	git("add", ".");
	git("commit", "-m", "fixture");
	assert.equal(check(directory, "main").length, 1);
	writeFileSync(path.join(directory, "src/new.ts"), `${directive}\n`);
	assert.throws(() => check(directory, "main"), /New lint-suppression: src\/new.ts/u);
	const addition = { ...allowance, file: "src/new.ts" };
	writeFileSync(path.join(directory, baselinePath), JSON.stringify([allowance, addition]));
	assert.throws(() => check(directory, "main"), /Baseline grew/u);
	unlinkSync(path.join(directory, "src/new.ts"));
	writeFileSync(path.join(directory, baselinePath), JSON.stringify([allowance]));
	unlinkSync(path.join(directory, allowance.file));
	assert.throws(() => check(directory, "main"), /Stale baseline/u);
	assert.deepEqual(check(directory, "main", true), []);
	assert.deepEqual(parseBaseline(readFileSync(path.join(directory, baselinePath), "utf8")), []);
	assert.throws(() => check(directory, "missing-base"));
});

await test("handwritten api packages retain compiler enforcement", () => {
	const file = "server/application/src/main/java/example/api/Detail.java";
	assert.equal(
		scanSource(file, '@SuppressWarnings("unchecked")\nclass Detail {}')[0]?.category,
		"java-suppression",
	);
	assert.equal(
		scanSource(
			"server/generated-clients/src/main/resources/template.ftl",
			'@SuppressWarnings("unused")',
		)[0]?.category,
		"java-suppression",
	);
});

await test("config-level suppressions cannot replace an inline directive", () => {
	const one = scanSource(
		".oxlintrc.json",
		'{"overrides":[{"files":["src/one.ts"],"rules":{"no-debugger":"off"}}]}',
	);
	const two = scanSource(
		".oxlintrc.json",
		'{"overrides":[{"files":["src/two.ts"],"rules":{"no-debugger":"off"}}]}',
	);
	assert.equal(one[0]?.category, "lint-configuration");
	assert.notDeepEqual(one[0].context, two[0]?.context);
	assert.equal(
		scanSource("tsconfig.json", '{"compilerOptions":{"strict":false}}')[0]?.category,
		"typescript-configuration",
	);
	assert.equal(
		scanSource(".oxfmtrc.json", '{"ignorePatterns":["src/new.ts"]}')[0]?.category,
		"format-configuration",
	);
	assert.equal(
		scanSource(".vale.ini", "[*.md]\nSTE.Words = NO")[0]?.category,
		"prose-configuration",
	);
});

await test("a cause cannot become a permanent exception through a baseline edit", () => {
	assert.throws(
		() =>
			assertShrinks(
				[{ ...allowance, reason: "root-cause: Remove the untyped boundary conversion." }],
				[allowance],
			),
		/Cause became an exception/u,
	);
});

await test("moving an identical directive to another function is a new allowance", () => {
	const first = `function one() {\n${directive}\ndebugger;\n}\nfunction two() { debugger; }`;
	const moved = `function one() { debugger; }\nfunction two() {\n${directive}\ndebugger;\n}`;
	const [before] = scanSource("src/example.ts", first);
	assert.ok(before);
	const baseline = [{ ...before, count: 1, owner: allowance.owner, reason: allowance.reason }];
	assert.throws(
		() => reconcile(scanSource("src/example.ts", moved), baseline, false),
		/New lint-suppression/u,
	);
});

await test("JSX apostrophes and nested templates cannot hide subsequent controls", () => {
	const source = [
		"const element = <p>Don't</p>;",
		`const template = \`outer \${\`nested \${1}\`} tail\`;`,
		directive,
		"debugger;",
		"/* @ts-ignore */",
		'const value: number = "wrong";',
		"try { read(); } catch {}",
	].join("\n");
	assert.deepEqual(
		scanSource("src/example.tsx", source).map(({ category }) => category),
		["lint-suppression", "typescript-suppression", "ignored-catch"],
	);
});

await test("shell equivalents of ignored failure remain counted", () => {
	for (const source of [
		"cmd || :",
		"cmd || exit 0",
		"set +eu",
		"set +ex",
		"set +o errexit",
		"set +o pipefail",
	]) {
		assert.equal(scanSource("script.sh", source)[0]?.category, "failure-bypass", source);
		assert.equal(
			scanSource(".github/workflows/example.yml", `run: |\n  ${source}`)[0]?.category,
			"failure-bypass",
			source,
		);
	}
});

await test("normal Markdown prose is not an implementation debt comment", () => {
	assert.deepEqual(
		scanSource(
			".changeset/example.md",
			"**Admins:** Until the backfill ends, the count is unknown.",
		),
		[],
	);
	assert.deepEqual(scanSource("docs/example.mdx", "## Wait until the review finishes"), []);
	assert.equal(
		scanSource("docs/example.mdx", "<!-- vale STE.Words = NO -->")[0]?.category,
		"prose-suppression",
	);
});

await test("reason wording corrections do not relocate the controlled statement", () => {
	const first = scanSource("src/protocol.ts", `${directive}\ndebugger;`);
	const corrected = scanSource(
		"src/protocol.ts",
		`${directive.replace("external debugger", "external inspection")}\ndebugger;`,
	);
	assert.deepEqual(corrected, first);
});

await test("policy identities include expiry and scope, without duplicating advisory text", () => {
	const exception = {
		image: "server",
		platform: "linux/amd64",
		vulnerability: "advisory-fixture",
		package: "pkg",
		installedVersion: "1",
		status: "not_affected",
		expires: "2027-01-01",
		digest: "sha256:fixture",
	};
	const first = scanSource(
		"security/vulnerability-policy.json",
		JSON.stringify({ exceptions: [exception] }),
	);
	const extended = scanSource(
		"security/vulnerability-policy.json",
		JSON.stringify({ exceptions: [{ ...exception, expires: "2027-02-01" }] }),
	);
	assert.notDeepEqual(first[0]?.source, extended[0]?.source);
	const [entry] = first;
	assert.ok(entry);
	assert.ok(!entry.source.includes("advisory-fixture"));
	assert.equal(
		scanSource("pnpm-workspace.yaml", 'minimumReleaseAgeExclude:\n  - "package@1"')[0]?.category,
		"release-age-exception",
	);
});

await test("static-analysis and formatting controls retain their comment syntax", () => {
	assert.equal(
		scanSource("src/Example.java", "class Example { int value; // NOPMD\n}")[0]?.category,
		"static-analysis-suppression",
	);
	assert.equal(
		scanSource("config.yaml", "# prettier-ignore\nvalues: []")[0]?.category,
		"format-suppression",
	);
	assert.ok(
		scanSource(".oxlintrc.json", '// prettier-ignore\n{"rules":{}}').some(
			({ category }) => category === "format-suppression",
		),
	);
});

await test("test framework settings are distinct from provider and parameterized payload data", () => {
	const source = [
		"const sdk = SettingsManager.inMemory({ retry: { enabled: false } });",
		'it.each([{ retry: true }])("wire fixture", () => {});',
		'it("body data", () => { const payload = { retry: true }; });',
		'it("options", () => {}, { retry: 3 });',
		"test.describe.configure({ retries: 2 });",
		'it("bounded", () => {}, 20_000);',
	].join("\n");
	assert.deepEqual(
		scanSource("src/example.test.ts", source).map(({ category }) => category),
		["test-retry", "test-retry", "test-timeout"],
	);
});

const timeouts = (source: string) =>
	scanSource("src/a.test.ts", source).map(({ category, source: text }) => [category, text]);
const nodeTest = (source: string) =>
	scanSource("src/a.test.ts", `import { test } from "node:test";\n${source}`).map(
		({ category, source: text }) => [category, text],
	);
const scope = (files: string, rule: string) => ({ files: [files], rules: { [rule]: "off" } });
const configuration = (...overrides: object[]) =>
	scanSource(".oxlintrc.json", JSON.stringify({ overrides }));

await test("a positional test timeout counts whether it is a literal or an expression", () => {
	assert.deepEqual(timeouts('const timeout=20_000; it("bounded",()=>{},timeout);'), [
		["test-timeout", "timeout"],
	]);
	for (const expression of ["5 * 1000", "limit()", "slow ? 30_000 : 5_000", "limits.slow"]) {
		assert.deepEqual(
			timeouts(`it("bounded", () => {}, ${expression});`),
			[["test-timeout", expression]],
			expression,
		);
	}
	assert.deepEqual(timeouts('it("body", () => {});'), []);
	assert.deepEqual(timeouts('it("default", () => {}, undefined);'), []);
	assert.deepEqual(timeouts('it("options", () => {}, { timeout: 500 });'), [
		["test-timeout", "500"],
	]);
	assert.deepEqual(timeouts('it("options", { timeout: 500 }, handler);'), [
		["test-timeout", "500"],
	]);
	assert.deepEqual(timeouts('it("options", {}, () => {});'), []);
	assert.deepEqual(scanSource("src/report.ts", "describe(field, before, after);"), []);
});

await test("a node:test callback is not a timeout", () => {
	assert.deepEqual(nodeTest('test("a", options, handler);'), []);
	assert.deepEqual(nodeTest('test("a", { concurrency: true }, () => {});'), []);
	assert.deepEqual(nodeTest('test("a", options, () => {});'), []);
	assert.deepEqual(nodeTest('test("a", { timeout: 1000 }, handler);'), [["test-timeout", "1000"]]);
	assert.deepEqual(
		scanSource("src/a.test.ts", 'import { it } from "vitest";\nit("a", options, handler);').map(
			({ category }) => category,
		),
		["test-timeout"],
	);
});

await test("a files-scoped override keeps its identity when a sibling override is removed", () => {
	const baseline = configuration(scope("a.ts", "no-debugger"), scope("b.ts", "no-alert")).map(
		(finding): Allowance => ({
			...finding,
			count: 1,
			owner: allowance.owner,
			reason: allowance.reason,
		}),
	);
	assert.equal(baseline.length, 2);
	assert.deepEqual(
		reconcile(configuration(scope("b.ts", "no-alert")), baseline, true),
		baseline.slice(1),
	);
	assert.throws(
		() =>
			reconcile(
				configuration(scope("b.ts", "no-debugger"), scope("b.ts", "no-alert")),
				baseline,
				true,
			),
		/New lint-configuration/u,
	);
	assert.notDeepEqual(
		configuration(scope("a.ts", "no-debugger"))[0]?.context,
		configuration(scope("b.ts", "no-debugger"))[0]?.context,
	);
});

await test("diagnostic browser retries still reject flaky passes on CI", () => {
	const output = execFileSync(
		process.execPath,
		[
			"--input-type=module",
			"-e",
			`
		const paths = ["webapp/playwright.config.ts", "extension/playwright.config.ts"];
		const profiles = [];
		for (const path of paths) {
			const { default: config } = await import(new URL(path, ${JSON.stringify(new URL("../", import.meta.url).href)}));
			profiles.push({ path, retries: config.retries, failOnFlakyTests: config.failOnFlakyTests });
		}
		process.stdout.write(JSON.stringify(profiles));
	`,
		],
		{ encoding: "utf8", env: environmentForGitFixture({ CI: "true" }) },
	);
	assert.deepEqual(parseJson(output), [
		{ path: "webapp/playwright.config.ts", retries: 1, failOnFlakyTests: true },
		{ path: "extension/playwright.config.ts", retries: 1, failOnFlakyTests: true },
	]);
});

await test("common empty recovery forms stay in the catch census", () => {
	for (const source of [
		"void read().catch(() => null);",
		"void read().catch(noop);",
		"try { read(); } catch { return undefined; }",
	]) {
		const wrapped = `function inspect() { ${source} }`;
		assert.equal(scanSource("src/example.ts", wrapped)[0]?.category, "ignored-catch", source);
	}
});

await test("the reviewed alternative suppression and test spellings stay in scope", () => {
	for (const [file, source, category] of [
		[
			"src/a.ts",
			"/** @ts-expect-error deliberate type fixture */\nconst value: string = 42;",
			"typescript-suppression",
		],
		[
			"src/a.test.ts",
			'test.fails("case", () => {}); test.fail(); test.runIf(false)("case", () => {}); ctx.skip();',
			"test-skip",
		],
		[
			"server/src/test/java/A.java",
			'@EnabledIfEnvironmentVariable(named="NEVER", matches=".+") class A {}',
			"test-skip",
		],
		["server/src/test/java/A.java", "Assumptions.assumeTrue(false);", "test-skip"],
		["server/src/test/java/A.java", "assumeThat(token).isNotBlank();", "test-skip"],
		["server/src/test/java/A.java", "assumingThat(false, () -> run());", "test-skip"],
		["server/src/test/java/A.java", "Assumptions.abort();", "test-skip"],

		[".oxlintrc.json", '{"rules":{"no-debugger":["allow"]}}', "lint-configuration"],
		["docs/.markdownlint-cli2.jsonc", '{"config":{"line-length":false}}', "prose-configuration"],
		[
			"webapp/vite.config.ts",
			'export default {test:{exclude:["flaky.test.ts"]}};',
			"test-tier-exclusion",
		],
		["vite.config.ts", 'const fmt = {ignorePatterns:["**/*.html"]};', "format-configuration"],
		["Dockerfile", "RUN check || true", "failure-bypass"],
		[".github/workflows/a.yml", "run: check || echo failed", "failure-bypass"],
	] as const) {
		assert.ok(
			scanSource(file, source).some((finding) => finding.category === category),
			`${file}: ${source}`,
		);
	}
});
