import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { test } from "node:test";

import { coverageSummary, verifyTerminalReport, visualVerdict } from "./report-chromatic.ts";

const buildUrl = "https://www.chromatic.com/build?appId=abc&number=123";
function terminalReport(status = "PASSED", testStatus = "PASSED", skipped = false) {
	return `<testsuites><testsuite tests="1" failures="0" errors="0" skipped="${skipped ? 1 : 0}"><properties><property name="buildNumber" value="123"/><property name="buildUrl" value="https://www.chromatic.com/build?appId=abc&amp;number=123"/><property name="buildStatus" value="${status}"/></properties><testcase><properties><property name="result" value="${testStatus}"/></properties>${skipped ? "<skipped/>" : ""}</testcase></testsuite></testsuites>`;
}

const tested = {
	CHROMATIC_BUILD_URL: buildUrl,
	CHROMATIC_OUTCOME: "success",
	CHROMATIC_CODE: "0",
	CHROMATIC_CAPTURED: "10",
	CHROMATIC_INHERITED: "20",
	CHROMATIC_TESTS: "30",
	CHROMATIC_ERRORS: "0",
	CHROMATIC_CHANGES: "0",
	CHROMATIC_INTERACTIONS: "0",
};

void test("distinguishes tested-build evidence, inherited and absent coverage", () => {
	assert.equal(visualVerdict(tested, terminalReport()).state, "tested-build");
	assert.match(
		coverageSummary(tested, terminalReport()),
		/do not prove new captures in this CI run/,
	);
	assert.equal(
		visualVerdict(
			{ ...tested, CHROMATIC_CAPTURED: "0" },
			terminalReport("ACCEPTED", "PASSED", true),
		).state,
		"inherited",
	);
	assert.equal(
		visualVerdict({ ...tested, CHROMATIC_CAPTURED: "0", CHROMATIC_INHERITED: "0" }).pass,
		false,
	);
});

void test("classifies account limits independently from service errors", () => {
	for (const code of ["5", "11", "12"])
		assert.equal(visualVerdict({ ...tested, CHROMATIC_CODE: code }).state, "quota-skipped");
	for (const code of ["3", "6", "201", "202", "220", "255", "999", ""])
		assert.equal(visualVerdict({ ...tested, CHROMATIC_CODE: code }).state, "unavailable");
	assert.equal(
		visualVerdict({ ...tested, CHROMATIC_CODE: "201", CHROMATIC_ERRORS: "1" }).state,
		"unavailable",
	);
	for (const code of ["1", "2"])
		assert.equal(visualVerdict({ ...tested, CHROMATIC_CODE: code }).state, "failed");
});

void test("missing/malformed evidence and action failures never approve coverage", () => {
	for (const key of Object.keys(tested).filter((name) => name !== "CHROMATIC_OUTCOME"))
		for (const value of [undefined, "", "undefined", "NaN", "-1", "1.5", "1e3", "9007199254740992"])
			assert.equal(visualVerdict({ ...tested, [key]: value }).pass, false, `${key}: ${value}`);
	for (const outcome of ["failure", "cancelled", "skipped", ""])
		assert.equal(visualVerdict({ ...tested, CHROMATIC_OUTCOME: outcome }).pass, false);
	for (const key of ["CHROMATIC_ERRORS", "CHROMATIC_INTERACTIONS"])
		assert.equal(visualVerdict({ ...tested, [key]: "1" }).pass, false);
	assert.equal(visualVerdict({ ...tested, CHROMATIC_TESTS: "0" }).pass, false);
});

void test("policy exemption applies only to an actually skipped action", () => {
	assert.equal(
		visualVerdict({ CHROMATIC_OUTCOME: "skipped", CHROMATIC_POLICY_SKIP: "true" }).state,
		"policy-skipped",
	);
	assert.equal(
		visualVerdict({ CHROMATIC_OUTCOME: "failure", CHROMATIC_POLICY_SKIP: "true" }).pass,
		false,
	);
});

void test("summary links only to a safe Chromatic build URL", () => {
	assert.match(
		coverageSummary({
			...tested,
			CHROMATIC_BUILD_URL: "https://www.chromatic.com/build?appId=abc&number=123",
		}),
		/Open Chromatic build/,
	);
	for (const url of [
		// eslint-disable-next-line no-script-url -- Adversarial input must never become a summary link.
		"javascript:alert(1)",
		"https://evil.example/build",
		"https://www.chromatic.com.evil.example/build",
		"https://user:secret@www.chromatic.com/build",
		"undefined",
	])
		assert.doesNotMatch(
			coverageSummary({ ...tested, CHROMATIC_BUILD_URL: url }),
			/Open Chromatic build/,
		);
});

void test("CLI writes its summary and fails closed even with no outputs", () => {
	const dir = mkdtempSync(join(tmpdir(), "chromatic-report-"));
	try {
		for (const [index, [env, status, state]] of (
			[
				[tested, 0, "tested-build"],
				[{ ...tested, CHROMATIC_CODE: "11" }, 1, "quota-skipped"],
				[{ ...tested, CHROMATIC_OUTCOME: "failure", CHROMATIC_CODE: "201" }, 1, "unavailable"],
				[{}, 1, "unavailable"],
				[{ CHROMATIC_OUTCOME: "skipped", CHROMATIC_POLICY_SKIP: "true" }, 0, "policy-skipped"],
			] as const
		).entries()) {
			const summary = join(dir, `${index}.md`);
			mkdirSync(join(dir, "webapp"), { recursive: true });
			writeFileSync(join(dir, "webapp/chromatic-report.xml"), terminalReport());
			const run = spawnSync(process.execPath, [resolve("scripts/report-chromatic.ts")], {
				cwd: dir,
				env: { ...env, GITHUB_STEP_SUMMARY: summary },
				encoding: "utf8",
			});
			assert.equal(run.status, status, run.stderr);
			assert.match(readFileSync(summary, "utf8"), new RegExp(`coverage: ${state}`));
			assert.equal(run.stdout.includes("::error::"), status !== 0);
			assert.equal(run.stdout.includes("::warning::"), state === "policy-skipped");
		}
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});

void test("accepted/reused builds may retain a nonzero change count", () => {
	assert.equal(
		visualVerdict({ ...tested, CHROMATIC_CHANGES: "2" }, terminalReport("ACCEPTED")).state,
		"tested-build",
	);
	assert.equal(
		visualVerdict({ ...tested, CHROMATIC_CHANGES: "2", CHROMATIC_CODE: "1" }).pass,
		false,
	);
});

void test("publish-only builds cannot pass from positive counters and exit zero", () => {
	const cases = Array.from(
		{ length: 1926 },
		(_, index) =>
			`<testcase><properties><property name="result" value="${index < 266 ? "IN_PROGRESS" : "PASSED"}"/></properties>${index < 266 ? "" : "<skipped/>"}</testcase>`,
	).join("");
	const xml = `<testsuites><testsuite tests="1926" skipped="1660" errors="0" failures="0"><properties><property name="buildNumber" value="5185"/><property name="buildStatus" value="IN_PROGRESS"/><property name="buildUrl" value="https://www.chromatic.com/build?appId=abc&amp;number=5185"/></properties>${cases}</testsuite></testsuites>`;
	const env = {
		...tested,
		CHROMATIC_CAPTURED: "266",
		CHROMATIC_INHERITED: "0",
		CHROMATIC_TESTS: "1820",
		CHROMATIC_BUILD_URL: "https://www.chromatic.com/build?appId=abc&number=5185",
	};
	assert.equal(visualVerdict(env, xml).pass, false);
	assert.match(coverageSummary(env, xml), /IN_PROGRESS/);
	assert.doesNotMatch(coverageSummary(env, xml), /passed without errors/);
});

void test("terminal report and child outcomes are required, independently of counts", () => {
	for (const status of [
		"IN_PROGRESS",
		"PENDING",
		"BROKEN",
		"FAILED",
		"CANCELLED",
		"DENIED",
		"unknown",
	]) {
		assert.equal(visualVerdict(tested, terminalReport(status)).pass, false);
		assert.equal(visualVerdict(tested, terminalReport("PASSED", status)).pass, false);
	}
	assert.equal(visualVerdict(tested).pass, false);
	for (const xml of [
		"",
		"<testsuites>",
		"<testsuites/>",
		terminalReport().replace('tests="1"', 'tests="2"'),
		terminalReport().replace('errors="0"', 'errors="1"'),
		terminalReport().replace("</testcase>", "<error/></testcase>"),
	])
		assert.equal(visualVerdict(tested, xml).pass, false);
	assert.match(
		verifyTerminalReport(
			terminalReport(),
			"https://www.chromatic.com/build?appId=other&number=123",
		) ?? "",
		/does not identify/,
	);
});

void test("clear step prevents an old successful report approving a skipped upload", () => {
	const dir = mkdtempSync(join(tmpdir(), "chromatic-stale-"));
	try {
		mkdirSync(join(dir, "webapp"));
		writeFileSync(join(dir, "webapp/chromatic-report.xml"), terminalReport());
		const script = resolve("scripts/report-chromatic.ts");
		assert.equal(spawnSync(process.execPath, [script, "--clear"], { cwd: dir }).status, 0);
		const result = spawnSync(process.execPath, [script], {
			cwd: dir,
			env: tested,
			encoding: "utf8",
		});
		assert.equal(result.status, 1);
		assert.match(result.stdout, /Missing structured Chromatic report evidence/);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});
