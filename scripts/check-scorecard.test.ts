import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

import { checkScorecard } from "./check-scorecard.ts";
import { asArray, asRecord, readJsonFile } from "./lib/json.ts";

const baseline = asRecord(await readJsonFile("security/scorecard-baseline.json"), "baseline");
const now = Date.parse("2026-09-05T19:00:22Z");
const assessment = () => ({
	date: "2026-09-05T19:00:22Z",
	repo: { name: baseline.repository },
	checks: structuredClone(asArray(baseline.checks, "checks")).map((check) =>
		asRecord(check, "check"),
	),
});

/**
 * The audit #1653 recorded, restated so that lowering a minimum is a deliberate edit here too, with
 * the scoring each check is charged under, so that reclassifying one is a deliberate edit as well.
 */
const enforced: Record<string, { score: number; scoredOver: string }> = {
	"Binary-Artifacts": { score: 10, scoredOver: "configuration" },
	"CI-Tests": { score: 10, scoredOver: "history" },
	"Code-Review": { score: 10, scoredOver: "history" },
	"Dangerous-Workflow": { score: 10, scoredOver: "configuration" },
	"Dependency-Update-Tool": { score: 10, scoredOver: "configuration" },
	License: { score: 10, scoredOver: "configuration" },
	Maintained: { score: 10, scoredOver: "history" },
	"Pinned-Dependencies": { score: 10, scoredOver: "configuration" },
	SAST: { score: 10, scoredOver: "history" },
	"Security-Policy": { score: 10, scoredOver: "configuration" },
	"Token-Permissions": { score: 10, scoredOver: "configuration" },
	Vulnerabilities: { score: 10, scoredOver: "configuration" },
	"Signed-Releases": { score: 8, scoredOver: "history" },
	"Branch-Protection": { score: 4, scoredOver: "configuration" },
};

const passes = { failures: [], reported: [] };

void test("the committed baseline enforces the recorded minimums under the recorded scoring", () => {
	const excluded = asRecord(baseline.excluded, "excluded");
	assert.deepEqual(
		Object.fromEntries(
			asArray(baseline.checks, "checks")
				.map((check) => asRecord(check, "check"))
				.filter((check) => !Object.hasOwn(excluded, String(check.name)))
				.map((check) => [check.name, { score: check.score, scoredOver: check.scoredOver }]),
		),
		enforced,
	);
});

void test("the committed baseline passes and improvements do not compensate for regressions", () => {
	assert.deepEqual(checkScorecard(baseline, assessment(), now), passes);
	const value = assessment();
	for (const check of value.checks) {
		if (check.name === "Branch-Protection") check.score = 10;
		if (check.name === "Pinned-Dependencies") check.score = 9;
	}
	assert.deepEqual(checkScorecard(baseline, value, now), {
		failures: ["Pinned-Dependencies: 9 (minimum 10)"],
		reported: [],
	});
});

void test("each enforced check independently rejects any drop or missing evidence", () => {
	for (const [name, { score: minimum }] of Object.entries(enforced)) {
		const value = assessment();
		assert.deepEqual(
			checkScorecard(
				baseline,
				{ ...value, checks: value.checks.filter((check) => check.name !== name) },
				now,
			),
			{ failures: [`${name}: missing (minimum ${minimum})`], reported: [] },
		);
		for (const check of value.checks) if (check.name === name) check.score = minimum - 1;
		assert.deepEqual(checkScorecard(baseline, value, now), {
			failures: [`${name}: ${minimum - 1} (minimum ${minimum})`],
			reported: [],
		});
	}
});

void test("a push answers for the configuration its commit left and no other event forgives", () => {
	const dropped = (name: keyof typeof enforced) => {
		const value = assessment();
		for (const check of value.checks)
			if (check.name === name) check.score = Number(enforced[name]?.score) - 1;
		return value;
	};
	assert.deepEqual(checkScorecard(baseline, dropped("SAST"), now, "push"), {
		failures: [],
		reported: ["SAST: 9 (minimum 10)"],
	});
	assert.deepEqual(checkScorecard(baseline, dropped("Branch-Protection"), now, "push"), {
		failures: ["Branch-Protection: 3 (minimum 4)"],
		reported: [],
	});
	for (const event of ["schedule", "workflow_dispatch", "branch_protection_rule", undefined])
		assert.deepEqual(checkScorecard(baseline, dropped("SAST"), now, event), {
			failures: ["SAST: 9 (minimum 10)"],
			reported: [],
		});
});

void test("only the four deliberate exclusions may drop without failure", () => {
	const value = assessment();
	for (const check of value.checks)
		if (["Contributors", "CII-Best-Practices", "Fuzzing", "Packaging"].includes(String(check.name)))
			check.score = -1;
	assert.deepEqual(checkScorecard(baseline, value, now), passes);
});

void test("new checks do not silently become a new policy", () => {
	const value = assessment();
	value.checks.push({ name: "New-Check", score: 0 });
	assert.deepEqual(checkScorecard(baseline, value, now), passes);
});

void test("rejects stale, future, invalid and pre-baseline assessments", () => {
	for (const date of ["2026-08-01", "2026-09-04T18:00:00Z", "2026-09-06", "not-a-date"])
		assert.throws(() => checkScorecard(baseline, { ...assessment(), date }, now));
	assert.throws(() => checkScorecard(baseline, assessment(), now + 8 * 86_400_000 + 1), /stale/);
	assert.deepEqual(checkScorecard(baseline, assessment(), now + 8 * 86_400_000), passes);
});

void test("rejects malformed, duplicate and wrong-repository evidence", () => {
	for (const value of [null, {}, { ...assessment(), repo: { name: "github.com/attacker/repo" } }]) {
		assert.throws(() => checkScorecard(baseline, value, now));
	}
	for (const score of [null, "10", 11, -2, 9.5])
		assert.throws(() =>
			checkScorecard(baseline, { ...assessment(), checks: [{ name: "SAST", score }] }, now),
		);
	assert.throws(
		() =>
			checkScorecard(
				baseline,
				{
					...assessment(),
					checks: [
						{ name: "SAST", score: 10 },
						{ name: "SAST", score: 10 },
					],
				},
				now,
			),
		/duplicate/,
	);
});

void test("invalid policy cannot pass vacuously", () => {
	for (const change of [
		{ checks: [] },
		{ checks: [{ name: "SAST", score: -1 }], excluded: {} },
		{ checks: [{ name: "SAST", score: 10 }], excluded: { SAST: "All checks disabled" } },
		{ excluded: { SAST: "" } },
		{ excluded: { Typo: "reason" } },
		{ date: "invalid" },
		// An enforced check with no classification, or a broken one, is never charged by default.
		{ checks: [{ name: "SAST", score: 10 }], excluded: {} },
		{ checks: [{ name: "SAST", score: 10, scoredOver: "sometimes", reason: "why" }], excluded: {} },
		{ checks: [{ name: "SAST", score: 10, scoredOver: "history", reason: " " }], excluded: {} },
		{ checks: [{ name: "SAST", score: 10, reason: "why" }], excluded: {} },
	])
		assert.throws(() => checkScorecard({ ...baseline, ...change }, assessment(), now));
});

void test("the CLI charges a regression to its event, keeps the evidence, and fails on transport and JSON errors", async (t) => {
	const directory = await mkdtemp(join(tmpdir(), "scorecard-cli-"));
	t.after(() => rm(directory, { recursive: true, force: true }));
	await mkdir(join(directory, "security"));
	await writeFile(join(directory, "security/scorecard-baseline.json"), JSON.stringify(baseline));
	const preload = join(directory, "fetch.mjs");
	await writeFile(
		preload,
		`globalThis.fetch = async () => new Response(process.env.RESPONSE_BODY, { status: Number(process.env.RESPONSE_STATUS) });`,
	);
	const summary = join(directory, "summary.md");
	const evidence = join(directory, "tmp/scorecard-assessment.json");
	const current = { ...assessment(), date: new Date().toISOString() };
	const regression = {
		...current,
		checks: current.checks.filter((check) => check.name !== "SAST"),
	};
	const lowered = (name: string, score: number) => ({
		...current,
		checks: current.checks.map((check) => (check.name === name ? { ...check, score } : check)),
	});
	const window = JSON.stringify(lowered("SAST", 9));
	for (const [name, event, status, body, failure, expected] of [
		[
			"passing assessment",
			"schedule",
			200,
			JSON.stringify(current),
			false,
			/All enforced checks meet/,
		],
		["missing check", "schedule", 200, JSON.stringify(regression), true, /SAST: missing/],
		["a window score a push cannot have caused", "push", 200, window, false, /window of history/],
		[
			"the same window score once the window has had its chance",
			"schedule",
			200,
			window,
			true,
			/SAST: 9 \(minimum 10\)/,
		],
		[
			"a configuration score the push is answerable for",
			"push",
			200,
			JSON.stringify(lowered("Branch-Protection", 3)),
			true,
			/Branch-Protection: 3 \(minimum 4\)/,
		],
		["HTTP failure", "schedule", 503, "service unavailable", true, undefined],
		["invalid JSON", "schedule", 200, "not JSON", true, undefined],
	] as const) {
		await t.test(name, async () => {
			await rm(summary, { force: true });
			await rm(evidence, { force: true });
			const result = spawnSync(
				process.execPath,
				["--import", pathToFileURL(preload).href, resolve("scripts/check-scorecard.ts")],
				{
					cwd: directory,
					timeout: 10_000,
					encoding: "utf8",
					env: {
						...process.env,
						GITHUB_EVENT_NAME: event,
						GITHUB_STEP_SUMMARY: summary,
						RESPONSE_BODY: body,
						RESPONSE_STATUS: String(status),
					},
				},
			);
			assert.equal(result.status, failure ? 1 : 0, result.stderr);
			if (expected) {
				assert.deepEqual(JSON.parse(await readFile(evidence, "utf8")), JSON.parse(body));
				assert.match(await readFile(summary, "utf8"), expected);
			} else {
				await assert.rejects(readFile(evidence), { code: "ENOENT" });
			}
		});
	}
});
