import assert from "node:assert/strict";
import { mkdtempSync, realpathSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { test } from "node:test";

import { executionReportSchema } from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import {
	capturedLines,
	directProgram,
	SESSION_CANDIDATE,
	field,
	practiceIndexOf,
	readJsonFile,
	runRunner,
	stagePractices,
	stagedRequest,
} from "./program-host-runner.ts";

function resultsOf(cwd: string) {
	const body = stagedRequest(cwd);
	const results = field(body, "results");
	assert.ok(Array.isArray(results));
	return { body, results: results.map((result: unknown) => result) };
}

void test("the next session waits for the content of the one before it, not for its early response headers", async () => {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-opening-stagger-")));
	try {
		stagePractices(cwd, [
			{ slug: "leader" },
			{ slug: "follower" },
			{ slug: "other", readsSources: ["scm.pull-request.diff"] },
		]);
		const exit = await runRunner(
			cwd,
			{},
			{
				PI_HOST_STREAM_DELAYS: JSON.stringify({ leader: 500 }),
				PI_HOST_DELAYS: JSON.stringify({ leader: 500 }),
			},
		);
		assert.equal(exit.status, 0, exit.stderr);
		const events = capturedLines(cwd, "model-events");
		const position = (event: string, slug: string) => {
			const index = events.findIndex(
				(row) => field(row, "event") === event && field(row, "slug") === slug,
			);
			assert.ok(index !== -1, `${slug} must emit ${event}`);
			return index;
		};
		assert.ok(position("headers", "leader") < position("stream", "leader"));
		assert.ok(position("start", "follower") > position("stream", "leader"));
		assert.ok(position("start", "other") > position("stream", "leader"));
		assert.ok(position("start", "follower") < position("finish", "leader"));
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

void test("three isolated model sessions overlap, retain each candidate and receipt, and submit in practice order", async () => {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-")));
	try {
		stagePractices(cwd, [{ slug: "z-last" }, { slug: "a-first" }, { slug: "m-middle" }]);
		const exit = await runRunner(
			cwd,
			{},
			{
				PI_HOST_DELAYS: JSON.stringify({ "z-last": 1200, "a-first": 900, "m-middle": 600 }),
			},
		);
		assert.equal(exit.status, 0, exit.stderr);
		const starts = capturedLines(cwd, "model-start").map((row) => field(row, "at"));
		const finishes = capturedLines(cwd, "model-finish").map((row) => field(row, "at"));
		assert.equal(starts.length, 3);
		assert.equal(finishes.length, 3);
		assert.ok(starts.every((at): at is number => typeof at === "number"));
		assert.ok(finishes.every((at): at is number => typeof at === "number"));
		assert.ok(
			Math.max(...starts) < Math.min(...finishes),
			"all calls start before any call completes",
		);
		const elapsed = Math.max(...finishes) - Math.min(...starts);
		assert.ok(
			elapsed >= 1200 && elapsed < 2200,
			`model wall time ${elapsed}ms approaches max=1200ms, below sum=2700ms`,
		);
		const { body, results } = resultsOf(cwd);
		assert.deepEqual(
			results.map((result: unknown) => field(result, "practiceSlug")),
			["a-first", "m-middle", "z-last"],
		);
		assert.deepEqual(field(body, "nonResults"), []);
		for (const result of results) {
			const coverage = field(field(result, "candidate"), "coverage");
			assert.equal(field(coverage, "complete"), true);
			assert.equal(field(coverage, "reads"), 1);
		}
		assert.partialDeepStrictEqual(readJsonFile(cwd, "out/practice-coverage.json"), {
			evaluated: 3,
			eligible: 3,
		});
		assert.partialDeepStrictEqual(readJsonFile(cwd, "out/usage.json"), {
			totalCalls: 3,
			inputTokens: 3000,
			outputTokens: 300,
		});
		const notes = readFileSync(nodePath.join(cwd, "work/notes/review.md"), "utf8");
		assert.ok(notes.indexOf("a-first:") < notes.indexOf("m-middle:"));
		assert.ok(notes.indexOf("m-middle:") < notes.indexOf("z-last:"));
		assert.match(exit.stderr, /review\.paced sessions_at_once=\d+/u);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

for (const ownership of ["opening", "native-read", "sibling-only", "claimed-only"] as const) {
	void test(`swapped completion orders preserve candidates and non-results with change evidence owned by ${ownership}`, async () => {
		const admissions: unknown[] = [];
		for (const earlier of ["sibling", "native"] as const) {
			const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-evidence-")));
			try {
				const program = directProgram(`await ctx.evidence.record("change.json");`).replace(
					'consulted: ["scm.pull-request.core"]',
					'consulted: ["scm.pull-request.core", "scm.pull-request.diff"]',
				);
				stagePractices(cwd, [{ slug: "a-native" }, { slug: "b-sibling", program }]);
				if (ownership !== "opening") {
					writeFileSync(
						nodePath.join(cwd, "work/change/diff.patch"),
						readFileSync(nodePath.join(cwd, "work/change/diff.patch"), "utf8") +
							" diff context".repeat(6000),
					);
				}
				const candidate = {
					...SESSION_CANDIDATE,
					outcome: "DOES_NOT_APPLY",
					problems: undefined,
					citations: SESSION_CANDIDATE.problems[0]?.citations,
					evidence: {
						inapplicability: {
							consulted:
								ownership === "claimed-only"
									? ["scm.pull-request.core", "scm.pull-request.diff"]
									: ["scm.pull-request.core"],
							prerequisite: "user interface views",
							ruledOutBy: "The record names one changed Java file.",
						},
					},
				};
				const exit = await runRunner(
					cwd,
					ownership === "native-read" ? { "a-native": "read-change" } : {},
					{
						PI_HOST_FIRST_COMPLETION: earlier === "sibling" ? "b-sibling" : "a-native",
						PI_HOST_SESSION_CANDIDATE: JSON.stringify(candidate),
					},
				);
				assert.equal(exit.status, 0, exit.stderr);
				const { body, results } = resultsOf(cwd);
				const accepted = ownership === "opening" || ownership === "native-read";
				assert.deepEqual(
					results.map((result) => field(result, "practiceSlug")),
					accepted ? ["a-native", "b-sibling"] : ["b-sibling"],
				);
				assert.deepEqual(
					field(body, "nonResults"),
					accepted ? [] : [{ practiceSlug: "a-native", reason: "EXECUTION_FAILED" }],
				);
				assert.deepEqual(capturedLines(cwd, "reads"), [
					{
						slug: "a-native",
						path: ownership === "native-read" ? "work/change/diff.patch" : "evidence/metadata.json",
					},
				]);
				assert.equal(
					field(capturedLines(cwd, "prompts")[0], "changeSupplied"),
					ownership === "opening",
				);
				const sibling = results.find((result) => field(result, "practiceSlug") === "b-sibling");
				assert.partialDeepStrictEqual(field(sibling, "readReceipts"), [
					{
						sourceKind: "scm.pull-request.diff",
						sourcePath: "evidence/change.json",
						status: "COMPLETE",
					},
				]);
				const practices = practiceIndexOf(cwd);
				const nativeDone = exit.stderr.indexOf(
					`practice.program_finished practice=${practices.indexOf("a-native")} program=built_in`,
				);
				const siblingDone = exit.stderr.indexOf(
					`practice.program_finished practice=${practices.indexOf("b-sibling")} program=custom`,
				);
				assert.ok(nativeDone !== -1 && siblingDone !== -1);
				assert.equal(siblingDone < nativeDone, earlier === "sibling");
				admissions.push({ results, nonResults: field(body, "nonResults") });
			} finally {
				rmSync(cwd, { recursive: true, force: true });
			}
		}
		assert.deepEqual(admissions[0], admissions[1]);
	});
}

void test("the deadline retains finished work and ends running and queued practices", async () => {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-deadline-")));
	try {
		stagePractices(
			cwd,
			["z-running", "a-finished", "y-waiting", "b-queued"].map((slug) => ({ slug })),
		);
		const exit = await runRunner(
			cwd,
			{ "z-running": "hang", "y-waiting": "hang" },
			{ AGENT_BUDGET_MS: "6500", PI_HOST_COLD_LEADER: "y-waiting" },
		);
		assert.equal(exit.status, 1, exit.stderr);
		const { results } = resultsOf(cwd);
		const body = readJsonFile(cwd, "out/result.json");
		assert.deepEqual(capturedLines(cwd, "admission"), []);
		assert.deepEqual(
			results.map((result: unknown) => field(result, "practiceSlug")),
			["a-finished"],
		);
		assert.deepEqual(field(body, "nonResults"), [
			{ practiceSlug: "b-queued", reason: "DEADLINE" },
			{ practiceSlug: "y-waiting", reason: "DEADLINE" },
			{ practiceSlug: "z-running", reason: "DEADLINE" },
		]);
		assert.deepEqual(
			capturedLines(cwd, "prompts")
				.map((row) => field(row, "slug"))
				.toSorted((a, b) => String(a).localeCompare(String(b))),
			["a-finished", "y-waiting", "z-running"],
		);
		const report = executionReportSchema.parse(readJsonFile(cwd, "out/practice-scripts.json"));
		assert.equal(
			report.practices.find((practice) => practice.practiceSlug === "b-queued")?.stopReason,
			"deadline",
		);
		assert.partialDeepStrictEqual(readJsonFile(cwd, "out/practice-coverage.json"), {
			evaluated: 1,
			eligible: 4,
		});
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

void test("a call that no provider answers holds every other practice back and claims nothing", async () => {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-waiting-")));
	try {
		stagePractices(
			cwd,
			["a-waiting", "b-queued", "c-queued"].map((slug) => ({ slug })),
		);
		const exit = await runRunner(
			cwd,
			{ "a-waiting": "hang" },
			{ AGENT_BUDGET_MS: "6500", PI_HOST_COLD_LEADER: "a-waiting" },
		);
		assert.equal(exit.status, 76, exit.stderr);
		assert.deepEqual(
			capturedLines(cwd, "prompts").map((row) => field(row, "slug")),
			["a-waiting"],
		);
		assert.deepEqual(capturedLines(cwd, "admission"), []);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

void test("a served session lets the next practice start while another is still running", async () => {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-slots-")));
	try {
		const slugs = ["slow", "quick-one", "quick-two", "quick-three"];
		stagePractices(
			cwd,
			slugs.map((slug) => ({ slug })),
		);
		const exit = await runRunner(
			cwd,
			{},
			{
				PI_HOST_DELAYS: JSON.stringify({
					slow: 900,
					"quick-one": 150,
					"quick-two": 150,
					"quick-three": 150,
				}),
			},
		);
		assert.equal(exit.status, 0, exit.stderr);
		const starts = capturedLines(cwd, "model-start");
		const finishes = capturedLines(cwd, "model-finish");
		assert.ok(
			Number(
				field(
					starts.find((row) => field(row, "slug") === "quick-three"),
					"at",
				),
			) <
				Number(
					field(
						finishes.find((row) => field(row, "slug") === "slow"),
						"at",
					),
				),
		);
		assert.equal(resultsOf(cwd).results.length, 4);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

void test("a session stop failure preserves completed siblings for admission and starts no further practice", async () => {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-stop-")));
	try {
		stagePractices(
			cwd,
			["a-finished", "z-stop", "y-running", "b-queued"].map((slug) => ({ slug })),
		);
		const exit = await runRunner(
			cwd,
			{ "y-running": "hang" },
			{
				PI_HOST_STOP_FAILURE: "z-stop",
				PI_HOST_COLD_LEADER: "y-running",
				PI_HOST_DELAYS: JSON.stringify({ "z-stop": 150 }),
			},
		);
		assert.equal(exit.status, 1, exit.stderr);
		assert.deepEqual(capturedLines(cwd, "admission"), []);
		assert.deepEqual(
			resultsOf(cwd).results.map((result: unknown) => field(result, "practiceSlug")),
			["a-finished"],
		);
		assert.ok(!capturedLines(cwd, "prompts").some((row) => field(row, "slug") === "b-queued"));
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});
