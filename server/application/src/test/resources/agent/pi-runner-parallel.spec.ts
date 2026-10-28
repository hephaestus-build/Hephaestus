import assert from "node:assert/strict";
import { mkdtempSync, realpathSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { test } from "node:test";

import { executionReportSchema } from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import { partitionBudget, turnBudget } from "../../../main/resources/agent/pi-review-turns.ts";
import {
	capturedLines,
	directProgram,
	SESSION_CANDIDATE,
	field,
	readJsonFile,
	runRunner,
	stagePractices,
} from "./program-host-runner.ts";

function resultsOf(cwd: string) {
	const [request] = capturedLines(cwd, "admission");
	const body = field(request, "body");
	const results = field(body, "results");
	assert.ok(Array.isArray(results));
	return { body, results: results.map((result: unknown) => result) };
}

void test("followers ignore early response headers and wait for content while another opening starts", async () => {
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
				PI_PRACTICE_CONCURRENCY: "3",
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
		assert.ok(position("start", "other") < position("stream", "leader"));
		assert.ok(position("start", "follower") > position("stream", "leader"));
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
				PI_PRACTICE_CONCURRENCY: "3",
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
		assert.match(exit.stderr, /Practice concurrency: configured=3, effective=3/u);
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
						PI_PRACTICE_CONCURRENCY: "2",
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
				const nativeDone = exit.stderr.indexOf("a-native: built-in program");
				const siblingDone = exit.stderr.indexOf("b-sibling: custom program");
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

for (const limit of ["calls", "tokens"]) {
	void test(`concurrent sessions reserve disjoint group ${limit} and cannot starve a sibling`, async () => {
		const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-budget-")));
		try {
			stagePractices(cwd, [{ slug: "greedy-one" }, { slug: "greedy-two" }, { slug: "finished" }]);
			const exit = await runRunner(
				cwd,
				{ "greedy-one": "budget", "greedy-two": "budget" },
				{
					PI_PRACTICE_CONCURRENCY: "3",
					PI_PRACTICE_MODEL_CALLS: limit === "calls" ? "2" : "12",
					PI_PRACTICE_OUTPUT_TOKENS: limit === "tokens" ? "150" : "16000",
					PI_HOST_DELAYS: JSON.stringify({ finished: 100 }),
				},
			);
			assert.equal(exit.status, 0, exit.stderr);
			const { body, results } = resultsOf(cwd);
			assert.deepEqual(
				results.map((result: unknown) => field(result, "practiceSlug")),
				["finished"],
			);
			assert.deepEqual(field(body, "nonResults"), [
				{ practiceSlug: "greedy-one", reason: "BUDGET" },
				{ practiceSlug: "greedy-two", reason: "BUDGET" },
			]);
			if (limit === "tokens") {
				for (const slug of ["greedy-one", "greedy-two"]) {
					assert.deepEqual(
						capturedLines(cwd, "requests")
							.filter((row) => field(row, "slug") === slug)
							.map((row) => field(row, "maxTokens")),
						[150, 50],
					);
				}
			}
			const traces = field(readJsonFile(cwd, "out/runner-debug.json"), "turns");
			assert.ok(Array.isArray(traces));
			assert.equal(traces.length, 3);
			for (const trace of traces) {
				const budget = field(trace, "budget");
				const calls = field(trace, "calls");
				const tokens = field(trace, "outputTokens");
				assert.ok(typeof calls === "number" && typeof tokens === "number");
				assert.ok(calls <= Number(field(budget, "modelCalls")));
				assert.ok(tokens <= Number(field(budget, "outputTokens")));
			}
		} finally {
			rmSync(cwd, { recursive: true, force: true });
		}
	});
}

for (const cold of [false, true]) {
	void test(`the deadline retains finished work and ends running and queued practices with a ${cold ? "cold" : "streaming"} leader`, async () => {
		const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-deadline-")));
		try {
			stagePractices(
				cwd,
				["z-running", "a-finished", "y-running", "b-queued"].map((slug) => ({
					slug,
					...(cold && slug === "a-finished"
						? {
								readsSources: [
									"scm.pull-request.core",
									"scm.pull-request.diff",
									"scm.repository.tree",
								],
							}
						: {}),
				})),
			);
			const exit = await runRunner(
				cwd,
				{ "z-running": "hang", "y-running": "hang" },
				{
					PI_PRACTICE_CONCURRENCY: "2",
					AGENT_BUDGET_MS: "6500",
					PI_HOST_COLD_LEADER: cold ? "z-running" : "",
				},
			);
			assert.equal(exit.status, 0, exit.stderr);
			const { body, results } = resultsOf(cwd);
			assert.deepEqual(
				results.map((result: unknown) => field(result, "practiceSlug")),
				["a-finished"],
			);
			assert.deepEqual(field(body, "nonResults"), [
				{ practiceSlug: "b-queued", reason: "DEADLINE" },
				{ practiceSlug: "y-running", reason: "DEADLINE" },
				{ practiceSlug: "z-running", reason: "DEADLINE" },
			]);
			assert.deepEqual(
				capturedLines(cwd, "prompts")
					.map((row) => field(row, "slug"))
					.toSorted((a, b) => String(a).localeCompare(String(b))),
				cold ? ["a-finished", "z-running"] : ["a-finished", "y-running", "z-running"],
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
}

void test("group reservations sum to the original budget, including a small group's minimum and indivisible shares", () => {
	for (const practices of [1, 2, 3, 6]) {
		const budget = turnBudget(practices, { modelCalls: 3, outputTokens: 1001 });
		const shares = Array.from({ length: practices }, (_, position) =>
			partitionBudget(budget, practices, position),
		);
		assert.equal(
			shares.reduce((sum, work) => sum + work.modelCalls, 0),
			budget.modelCalls,
		);
		assert.equal(
			shares.reduce((sum, work) => sum + work.outputTokens, 0),
			budget.outputTokens,
		);
		assert.ok(shares.every((share) => share.modelCalls >= 3 && share.outputTokens >= 1001));
	}
});

void test("a free slot starts the next practice across groups while another practice is still running", async () => {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-slots-")));
	try {
		const slugs = ["slow", "quick-one", "quick-two", "quick-three"];
		stagePractices(
			cwd,
			slugs.map((slug) => ({ slug })),
		);
		const index = readJsonFile(cwd, "catalog/practices/index.json");
		assert.ok(Array.isArray(index));
		const groups = index.map((practice: unknown, position) => ({
			...recordOf(practice),
			group: `group-${position}`,
		}));
		writeFileSync(nodePath.join(cwd, "catalog/practices/index.json"), JSON.stringify(groups));
		const exit = await runRunner(
			cwd,
			{},
			{
				PI_PRACTICE_CONCURRENCY: "2",
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
		const events = [
			...starts.map((row) => ({ at: Number(field(row, "at")), change: 1 })),
			...finishes.map((row) => ({ at: Number(field(row, "at")), change: -1 })),
		].toSorted((a, b) => a.at - b.at || a.change - b.change);
		let active = 0;
		let peak = 0;
		for (const event of events) {
			active += event.change;
			peak = Math.max(peak, active);
		}
		assert.equal(active, 0);
		assert.equal(peak, 2);
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

function recordOf(value: unknown): Record<string, unknown> {
	assert.ok(typeof value === "object" && value !== null && !Array.isArray(value));
	return Object.fromEntries(Object.entries(value));
}

void test("a custom program's repeated review sessions spend only its reservation", async () => {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-parallel-custom-")));
	try {
		const program = `import { definePracticeScript } from "../lib/core/practice-script.ts";
export default definePracticeScript({ meta: {}, async run(ctx) {
    await ctx.review({});
    return ctx.review({});
} });`;
		stagePractices(cwd, [{ slug: "script", program }, { slug: "sibling" }, { slug: "other" }]);
		const exit = await runRunner(
			cwd,
			{},
			{ PI_PRACTICE_CONCURRENCY: "3", PI_PRACTICE_MODEL_CALLS: "2" },
		);
		assert.equal(exit.status, 0, exit.stderr);
		assert.deepEqual(
			resultsOf(cwd).results.map((result: unknown) => field(result, "practiceSlug")),
			["other", "script", "sibling"],
		);
		const report = executionReportSchema.parse(readJsonFile(cwd, "out/practice-scripts.json"));
		assert.equal(
			report.practices.find((practice) => practice.practiceSlug === "script")?.calls.review,
			2,
		);
		assert.partialDeepStrictEqual(readJsonFile(cwd, "out/usage.json"), {
			totalCalls: 4,
			outputTokens: 400,
		});
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
				PI_PRACTICE_CONCURRENCY: "2",
				PI_HOST_STOP_FAILURE: "z-stop",
				PI_HOST_DELAYS: JSON.stringify({ "z-stop": 150 }),
			},
		);
		assert.equal(exit.status, 0, exit.stderr);
		assert.deepEqual(
			resultsOf(cwd).results.map((result: unknown) => field(result, "practiceSlug")),
			["a-finished"],
		);
		assert.ok(!capturedLines(cwd, "prompts").some((row) => field(row, "slug") === "b-queued"));
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});
