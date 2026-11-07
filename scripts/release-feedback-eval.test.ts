import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";
import { fileURLToPath } from "node:url";

import { parseCompositions } from "./feedback-eval/compositions.ts";
import { wilson95 } from "./feedback-eval/statistics.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const corpus = readFileSync(new URL("feedback-eval/compositions.json", import.meta.url), "utf8");
const set = parseCompositions(parseJson(corpus));
const occasionPanel = readFileSync(
	new URL("feedback-eval/occasion-cases.json", import.meta.url),
	"utf8",
);
const criteriaCorpus = readFileSync(
	new URL(
		"../server/application/src/test/resources/practices/criteria-cases.json",
		import.meta.url,
	),
	"utf8",
);
const privatePanel = readFileSync(
	new URL("feedback-eval/private-cases.json", import.meta.url),
	"utf8",
);

const temporaryDirectories: string[] = [];
after(() => {
	for (const directory of temporaryDirectories) {
		rmSync(directory, { recursive: true, force: true });
	}
});

function fixture() {
	const cwd = mkdtempSync(path.join(tmpdir(), "release-feedback-eval-"));
	temporaryDirectories.push(cwd);
	const environment = environmentForGitFixture();
	writeFileSync(path.join(cwd, ".gitignore"), "results/\nlabels.json\n");
	mkdirSync(path.join(cwd, "scripts/feedback-eval"), { recursive: true });
	writeFileSync(path.join(cwd, "scripts/feedback-eval/compositions.json"), corpus);
	mkdirSync(path.join(cwd, "docs/contributor"), { recursive: true });
	const rubric = readFileSync(
		new URL("../docs/contributor/composition-label-rubric.md", import.meta.url),
	);
	writeFileSync(path.join(cwd, "docs/contributor/composition-label-rubric.md"), rubric);
	writeFileSync(path.join(cwd, "scripts/feedback-eval/private-cases.json"), privatePanel);
	writeFileSync(path.join(cwd, "scripts/feedback-eval/occasion-cases.json"), occasionPanel);
	mkdirSync(path.join(cwd, "server/application/src/test/resources/practices"), { recursive: true });
	writeFileSync(
		path.join(cwd, "server/application/src/test/resources/practices/criteria-cases.json"),
		criteriaCorpus,
	);
	const git = (args: string[]) =>
		execFileSync("git", args, {
			cwd,
			encoding: "utf8",
			env: environment,
			maxBuffer: CAPTURE_LIMIT_BYTES,
		}).trim();
	git(["init", "--quiet"]);
	git(["add", "scripts", "server", "docs", ".gitignore"]);
	git([
		"-c",
		"user.name=Fixture",
		"-c",
		"user.email=fixture@example.invalid",
		"-c",
		"commit.gpgsign=false",
		"commit",
		"--quiet",
		"-m",
		"fixture",
	]);
	const commit = git(["rev-parse", "HEAD"]);
	const cases = set.cases.map((item) => {
		const reference = item.compositions.find((each) => each.id === "reference");
		assert.ok(reference);
		return {
			id: item.id,
			accepted: true,
			firstAccepted: true,
			servedModels: ["fixture-served"],
			failure: null,
			errors: [],
			review: reference.review,
		};
	});
	const privateCases = set.cases
		.filter((item) =>
			[
				"gh-a-01-menu-view-does-its-own-networking",
				"gh-a-11-label-claim-contradicted-by-code",
				"gh-c-09-autosave-ignored-errors",
				"mx-d-01-injection-in-description-ios",
			].includes(item.id),
		)
		.flatMap((item) =>
			["PRACTICE_PAGE", "CONVERSATION"].flatMap((destination) =>
				[1, 2].map((count) => {
					const id = `${item.id}/${destination}/${count}`;
					const withhold =
						item.id.includes("contradicted") || (destination === "PRACTICE_PAGE" && count === 1);
					return {
						id,
						accepted: true,
						failure: null,
						errors: [],
						servedModels: ["fixture-served"],
						review: {
							feedback: [
								{
									destination,
									practiceSlug: item.results[0]?.practice ?? "",
									action: withhold ? "WITHHOLD" : "NEW",
									basedOn: ["fixture-result"],
									withholdReason: withhold ? "BELOW_BAR" : undefined,
									title: withhold ? undefined : "A supported concern",
									body:
										!withhold && destination === "PRACTICE_PAGE"
											? "A supported concern."
											: undefined,
									nextStep:
										!withhold && destination === "PRACTICE_PAGE"
											? "Try the evidence-backed remedy."
											: undefined,
									notes:
										!withhold && destination === "CONVERSATION"
											? {
													situation: "A supported concern.",
													capability: "A useful skill.",
													evidenceSummary: "Two synthetic examples.",
													inConversationLearning: "Discuss the reason.",
												}
											: undefined,
								},
							],
						},
					};
				}),
			),
		);
	const run = {
		version: 1,
		commit,
		model: {
			id: "fixture",
			reasoningEffort: "MEDIUM",
			maxOutputTokens: 32_768,
			endpointSha256: "a".repeat(64),
		},
		cases,
		privateCases,
	};
	const labels = {
		version: 1,
		source: "ai-drafted",
		runSha256: "",
		labeller: {
			model: "independent-fixture",
			rubricSha256: createHash("sha256").update(rubric).digest("hex"),
		},
		labels: [...cases, ...privateCases].map((item) => ({
			caseId: item.id,
			good: true,
			codes: [] as string[],
			why: "Fixture reference.",
		})),
	};
	const occasionCases = [
		[
			"release-permission-scaffold",
			"declares-permissions-truthfully-at-point-of-use",
			"DOES_NOT_APPLY",
		],
		[
			"release-permission-actual-access",
			"declares-permissions-truthfully-at-point-of-use",
			"NOT_MET",
		],
		["release-permission-point-of-use", "declares-permissions-truthfully-at-point-of-use", "MET"],
		["release-merge-substantive-account", "merge-confirms-the-linked-issue-outcome", "MET"],
		["release-merge-unaccounted-outcome", "merge-confirms-the-linked-issue-outcome", "NOT_MET"],
		["release-unmerged-outcome", "merge-confirms-the-linked-issue-outcome", "DOES_NOT_APPLY"],
	].map((row) => {
		const [id, slug, outcome] = row;
		assert.ok(id !== undefined && slug !== undefined && outcome !== undefined);
		return {
			id,
			actual: { [slug]: outcome },
			runnerModel: "fixture",
			actualSeverity: id === "release-merge-unaccounted-outcome" ? { [slug]: "MINOR" } : {},
		};
	});

	const execution = {
		version: 1,
		commit,
		model: run.model,
		compositions: [...cases, ...privateCases].map((row) => ({ id: row.id, status: 0 })),
		occasions: occasionCases.map((row) => ({ id: row.id, status: 0 })),
	};
	const score = (candidate: unknown = run, staleLabels = false) => {
		mkdirSync(path.join(cwd, "results"), { recursive: true });
		writeFileSync(
			path.join(cwd, "results/occasions.json"),
			JSON.stringify({ version: 1, commit, model: run.model, cases: occasionCases }),
		);
		writeFileSync(path.join(cwd, "results/execution.json"), JSON.stringify(execution));
		const bytes = JSON.stringify(candidate);
		writeFileSync(path.join(cwd, "results/compositions.json"), bytes);
		labels.runSha256 = staleLabels
			? "0".repeat(64)
			: createHash("sha256").update(bytes).digest("hex");
		writeFileSync(path.join(cwd, "labels.json"), JSON.stringify(labels));
		return spawnSync(
			process.execPath,
			[
				fileURLToPath(new URL("release-feedback-eval.ts", import.meta.url)),
				"--score-only",
				"--output",
				"results",
				"--labels",
				"labels.json",
			],
			{ cwd, encoding: "utf8", env: environment, maxBuffer: CAPTURE_LIMIT_BYTES },
		);
	};
	return { cwd, run, labels, score, occasionCases, execution };
}

void test("a complete fixed panel records its commit, model, rates and interval limits", () => {
	const f = fixture();
	const result = f.score();
	assert.equal(result.status, 0, result.stderr);
	const report = readFileSync(path.join(f.cwd, "results/results.json"), "utf8");
	assert.match(report, /no-findings-on-panel/u);
	assert.match(report, /not deployment confidence bounds/u);
	assert.match(report, /practice-page/u);
	assert.match(report, new RegExp(f.run.commit, "u"));
	assert.deepEqual(wilson95(0, 60)?.[0], 0);
	assert.ok((wilson95(0, 60)?.[1] ?? 1) > 0.05);
	assert.equal(wilson95(60, 60)?.[1], 1);
});

void test("missing cases, another commit, and bad labels cannot pass a release eval", () => {
	for (const failure of [
		"missing",
		"commit",
		"quality",
		"provider",
		"private-missing",
		"occasion-missing",
		"occasion-wrong",
		"occasion-mixed-model",
		"label-source",
	] as const) {
		const f = fixture();
		if (failure === "missing") {
			f.run.cases.pop();
			f.labels.labels = f.labels.labels.filter(
				(label) =>
					f.run.cases.some((item) => item.id === label.caseId) || label.caseId.includes("/"),
			);
		}
		if (failure === "private-missing") {
			f.run.privateCases.pop();
		}
		if (failure === "occasion-missing") {
			f.occasionCases.pop();
		}
		if (failure === "occasion-mixed-model") {
			const row = f.occasionCases[0];
			assert.ok(row);
			row.runnerModel = "another-runner-model";
		}
		if (failure === "occasion-wrong") {
			const row = f.occasionCases[0];
			assert.ok(row);
			row.actual = { "declares-permissions-truthfully-at-point-of-use": "NOT_MET" };
		}
		if (failure === "label-source") {
			f.labels.source = "human";
		}
		if (failure === "commit") {
			f.run.commit = "0".repeat(40);
		}
		if (failure === "quality") {
			const label = f.labels.labels[0];
			assert.ok(label);
			label.good = false;
			label.codes = ["T1"];
		}
		if (failure === "provider") {
			const entry = f.run.cases[0];
			assert.ok(entry);
			entry.accepted = false;
		}
		const result = f.score();
		assert.equal(result.status, 1, `${failure}: ${result.stderr}`);
		assert.match(
			readFileSync(path.join(f.cwd, "results/results.json"), "utf8"),
			/"status": "findings"/u,
		);
	}
});

void test("a malformed refused draft remains a recorded finding rather than aborting the report", () => {
	const f = fixture();
	const candidate = {
		...f.run,
		cases: f.run.cases.map((entry, index) =>
			index === 0 ? { ...entry, accepted: false, review: {} } : entry,
		),
	};
	const result = f.score(candidate);
	assert.equal(result.status, 1, result.stderr);
	assert.match(
		readFileSync(path.join(f.cwd, "results/results.json"), "utf8"),
		/"status": "findings"/u,
	);
});

void test("untracked source cannot acquire the committed checkout's provenance", () => {
	const f = fixture();
	writeFileSync(path.join(f.cwd, "uncommitted.ts"), "export const changed = true;\n");
	const result = f.score();
	assert.equal(result.status, 1);
	assert.match(result.stderr, /Commit the source before measuring a release/u);
});

void test("missing assessment output stays separate from an incorrect assessment", () => {
	const f = fixture();
	f.occasionCases.pop();
	const result = f.score();
	assert.equal(result.status, 1);
	const report = readFileSync(path.join(f.cwd, "results/results.json"), "utf8");
	assert.match(report, /no assessment result; execution did not complete/u);
	assert.match(report, /"completed":\s*\{\s*"successes": 5,\s*"total": 6/u);
	assert.match(report, /"correct":\s*\{\s*"successes": 5,\s*"total": 5/u);
});

void test("scoring retains execution failures and rejects stale or incomplete provenance", () => {
	for (const variant of [
		"failed-live",
		"stale-labels",
		"missing-model",
		"same-labeller",
		"wrong-rubric",
		"missing-served",
	] as const) {
		const f = fixture();
		if (variant === "failed-live") {
			const row = f.execution.compositions[0];
			assert.ok(row);
			row.status = 1;
		}
		if (variant === "same-labeller") {
			f.labels.labeller.model = f.run.model.id;
		}
		if (variant === "wrong-rubric") {
			f.labels.labeller.rubricSha256 = "0".repeat(64);
		}
		if (variant === "missing-served") {
			const row = f.run.cases[0];
			assert.ok(row);
			row.servedModels = [];
		}
		const candidate = variant === "missing-model" ? { ...f.run, model: null } : f.run;
		const result = f.score(candidate, variant === "stale-labels");
		assert.equal(result.status, 1, `${variant}: ${result.stderr}`);
	}
});
void test("private decisions and mechanical defects cannot be hidden by good labels", () => {
	for (const variant of ["withhold-all", "empty", "long-url"] as const) {
		const f = fixture();
		for (const entry of f.run.privateCases) {
			entry.review.feedback =
				variant === "empty"
					? []
					: entry.review.feedback.map((piece) => ({
							...piece,
							...(variant === "withhold-all"
								? { action: "WITHHOLD" }
								: { body: `${"x".repeat(601)} https://example.com` }),
						}));
		}
		const result = f.score();
		assert.equal(result.status, 1, result.stderr);
		const report = readFileSync(path.join(f.cwd, "results/results.json"), "utf8");
		assert.match(
			report,
			variant === "long-url" ? /omits mechanical codes Q6, Q9/u : /private action differs/u,
		);
	}
});

void test("a changed endpoint or requested model is refused before occasion evidence is replaced", () => {
	const f = fixture();
	assert.equal(f.score().status, 0);
	const before = readFileSync(path.join(f.cwd, "results/occasions.json"), "utf8");
	const result = spawnSync(
		process.execPath,
		[fileURLToPath(new URL("release-feedback-eval.ts", import.meta.url)), "--output", "results"],
		{
			cwd: f.cwd,
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
			env: {
				...environmentForGitFixture(),
				HEPHAESTUS_LIVE_LLM_API_KEY: "fixture-key",
				HEPHAESTUS_LIVE_LLM_MODEL: "another-model",
				HEPHAESTUS_LIVE_LLM_BASE_URL: "https://fixture.invalid",
			},
		},
	);
	assert.equal(result.status, 1);
	assert.match(result.stderr, /another commit, model or endpoint/u);
	assert.equal(readFileSync(path.join(f.cwd, "results/occasions.json"), "utf8"), before);
});
