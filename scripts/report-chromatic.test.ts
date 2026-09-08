import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { coverageSummary, visualVerdict } from "./report-chromatic.ts";

const tested = {
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
	assert.equal(visualVerdict(tested).state, "tested-build");
	assert.match(coverageSummary(tested), /do not prove new captures in this CI run/);
	assert.equal(visualVerdict({ ...tested, CHROMATIC_CAPTURED: "0" }).state, "inherited");
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
			const run = spawnSync(process.execPath, ["scripts/report-chromatic.ts"], {
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
	assert.equal(visualVerdict({ ...tested, CHROMATIC_CHANGES: "2" }).state, "tested-build");
	assert.equal(
		visualVerdict({ ...tested, CHROMATIC_CHANGES: "2", CHROMATIC_CODE: "1" }).pass,
		false,
	);
});
