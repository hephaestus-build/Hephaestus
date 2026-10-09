import assert from "node:assert/strict";
import { cp, mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, before, describe, it } from "node:test";

import { isPracticeModule } from "./lib/practice-contract.ts";
import type { DiffFile, PracticeScript } from "./lib/types.ts";

const SCRIPTS_DIR = path.resolve(
	import.meta.dirname,
	"../../../server/application/src/main/resources/practices/precompute",
);
const LIB_DIR = path.resolve(import.meta.dirname, "lib");

const tempDirs: string[] = [];

async function createTempDir(prefix: string): Promise<string> {
	const dir = await mkdtemp(path.join(tmpdir(), prefix));
	tempDirs.push(dir);
	return dir;
}

/**
 * Stage a single script in a work dir laid out like the runner (`practices/<script>` + symlinked `lib/`)
 * so its `../lib/types` import resolves, then import the staged copy and return its default export.
 */
async function loadScript(name: string): Promise<PracticeScript> {
	const work = await createTempDir(`pc-script-${name}-`);
	await mkdir(path.join(work, "practices"), { recursive: true });
	await writeFile(path.join(work, "package.json"), '{"type":"module"}\n');
	await symlink(LIB_DIR, path.join(work, "lib"));
	const staged = path.join(work, "practices", `${name}.ts`);
	await cp(path.join(SCRIPTS_DIR, `${name}.ts`), staged);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) {
		throw new Error(`${name} does not export a default function`);
	}
	return mod.default;
}

/** A path the diff touched. The scripts under test read the changed paths, not the hunk bodies. */
function changedFile(file: string): DiffFile {
	return { path: file, addedLines: new Map(), removedLines: new Map(), hunks: [] };
}

afterEach(async () => {
	await Promise.all(
		tempDirs.splice(0).map(async (dir) => rm(dir, { recursive: true, force: true })),
	);
});

void describe("ships-tests-with-the-change", () => {
	let run: PracticeScript;
	before(async () => {
		run = await loadScript("ships-tests-with-the-change");
	});

	void it("classifies the changed source paths by convention, Xcode test target folders included", async () => {
		const paths = [
			"App/Latest.swift",
			"App/Contest.swift",
			"CourseUITests/Smoke.swift",
			"AppTests/ScoreTests.swift",
			"README.md",
		];
		const diff = new Map(paths.map((file) => [file, changedFile(file)]));

		// No checkout at all: the question is answered from the diff, with no repository census.
		const result = await run(path.join(await createTempDir("pc-empty-"), "missing"), diff, {});

		assert.deepEqual(result.metrics, { diffProductionFiles: 2, diffTestFiles: 2 });
		assert.match(
			result.directions[0] ?? "",
			/^The diff changes 2 production source file\(s\) and 2 test file\(s\), by path: CourseUITests\/Smoke\.swift, AppTests\/ScoreTests\.swift\./u,
		);
	});

	void it("judges untested production code without a test target instead of abstaining", async () => {
		const diff = new Map([["App/Quiz.swift", changedFile("App/Quiz.swift")]]);

		const result = await run(await createTempDir("pc-no-tests-"), diff, {});

		assert.deepEqual(result.metrics, { diffProductionFiles: 1, diffTestFiles: 0 });
		const directions = result.directions.join(" ");
		assert.match(directions, /does not decide this practice: judge the changed behaviour/u);
		assert.doesNotMatch(directions, /cannot be assessed/u);
	});

	void it("says nothing about a change with no source file", async () => {
		const diff = new Map([["README.md", changedFile("README.md")]]);

		const result = await run(await createTempDir("pc-docs-"), diff, {});

		assert.deepEqual(result.directions, []);
	});
});

void describe("issue classification across practices", () => {
	const names = [
		"issue-has-checkable-outcome",
		"issue-states-an-actionable-problem",
		"issue-scoped-to-single-concern",
	];

	for (const { name, metadata, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } of [
		{
			name: "empty deliverable",
			metadata: { title: "Export billing reports", labels: ["FEATURE"] },
			emptyOrTitleEcho: 1,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "title repeated with different punctuation",
			metadata: {
				title: "Export billing reports to CSV",
				body: "EXPORT billing reports: to CSV!",
				issue_type: "Task",
			},
			emptyOrTitleEcho: 1,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "detailed body that begins with the title",
			metadata: {
				title: "Add sign-in",
				body: "Add sign-in so returning members can save a draft. Done when the draft survives reopening.",
				labels: ["type::User Story"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "short concrete sentence",
			metadata: { title: "Invoice PDF", body: "Text no longer overlaps." },
			emptyOrTitleEcho: 0,
			hasDeliverableType: 0,
			looksUmbrella: 0,
		},
		{
			name: "non-Latin body that is not the non-Latin title",
			metadata: { title: "报告", body: "导出月度报告" },
			emptyOrTitleEcho: 0,
			hasDeliverableType: 0,
			looksUmbrella: 0,
		},
		{
			name: "substantive umbrella",
			metadata: {
				title: "Billing improvements",
				body: "Split the invoice work into independently deliverable child issues with their own acceptance criteria.",
				labels: ["REQUIREMENT"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 1,
		},
		{
			name: "substantive body with an omitted title",
			metadata: {
				body: "The PDF contains overlapping text when a customer's address spans more than three lines.",
				labels: ["BUG"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "substantive body with an empty title",
			metadata: {
				title: "",
				body: "The PDF contains overlapping text when a customer's address spans more than three lines.",
				labels: ["BUG"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "substantive body with a non-Latin title",
			metadata: {
				title: "报告",
				body: "The PDF contains overlapping text when a customer's address spans more than three lines.",
				labels: ["BUG"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "substantive untyped issue",
			metadata: {
				title: "Unreadable invoice",
				body: "The PDF contains overlapping text when a customer's address spans more than three lines.",
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 0,
			looksUmbrella: 0,
		},
	]) {
		void it(`preserves shared facts for a ${name}`, async () => {
			for (const scriptName of names) {
				const run = await loadScript(scriptName);
				const result = await run("", new Map(), metadata);
				assert.equal(result.metrics.emptyOrTitleEcho, emptyOrTitleEcho, scriptName);
				assert.deepEqual(result.hints, [], scriptName);
				if (scriptName !== "issue-scoped-to-single-concern") {
					assert.equal(result.metrics.hasDeliverableType, hasDeliverableType, scriptName);
					assert.equal(result.metrics.looksUmbrella, looksUmbrella, scriptName);
				}
				const said = result.directions.join("\n");
				assert.equal(
					/body is empty|repeats the title and says nothing else/u.test(said),
					emptyOrTitleEcho === 1,
					`${scriptName}: ${name}`,
				);
				assert.doesNotMatch(said, /no quotable deliverable|nothing to build from/iu, scriptName);
			}
		});
	}

	void it("keeps an unreported sub-issue rollup unknown, apart from a reported zero and a total without its completed count", async () => {
		for (const scriptName of [
			"breaks-large-work-into-trackable-subtasks",
			"issue-scoped-to-single-concern",
		]) {
			const run = await loadScript(scriptName);
			const issue = { title: "Course checklist", body: "- [ ] Clarify\n- [ ] Reply\n- [ ] Defer" };
			const cases: [
				Record<string, number | null>,
				RegExp,
				{ subIssuesTotal?: number; subIssuesCompleted?: number },
			][] = [
				[{}, /sub-issue rollup not reported by the provider \(unknown, not zero\)/u, {}],
				[
					{ sub_issues_total: null, sub_issues_completed: null },
					/sub-issue rollup not reported by the provider \(unknown, not zero\)/u,
					{},
				],
				[
					{ sub_issues_total: 0, sub_issues_completed: 0 },
					/0 sub-issue\(s\), 0 completed/u,
					{ subIssuesTotal: 0, subIssuesCompleted: 0 },
				],
				[
					{ sub_issues_total: 3, sub_issues_completed: null },
					/3 sub-issue\(s\), completed count not reported/u,
					{ subIssuesTotal: 3 },
				],
			];
			for (const [rollup, text, metrics] of cases) {
				const result = await run("", new Map(), { ...issue, ...rollup });
				assert.match(result.directions.join("\n"), text, scriptName);
				assert.equal(result.metrics.subIssuesTotal, metrics.subIssuesTotal, scriptName);
				assert.equal(result.metrics.subIssuesCompleted, metrics.subIssuesCompleted, scriptName);
				assert.equal(result.metrics.taskCheckboxes ?? result.metrics.checkboxes, 3, scriptName);
				assert.doesNotMatch(result.directions.join("\n"), /looks large|strong candidate/iu);
			}
		}
	});
});

void it("testing guidance keeps words separated by template comments apart", async () => {
	const run = await loadScript("states-how-to-verify-the-change");
	const result = await run("", new Map(), {
		title: "",
		source_branch: "",
		body: "## How to test\nn<!-- template -->a",
	});
	assert.match(result.directions.join("\n"), /holds 1 line\(s\) of author text/u);
});

void it("linked-work analysis reads only explicitly supplied context, never a repository-derived fallback", async () => {
	const root = await createTempDir("linked-work-context-");
	const analyse = await loadScript("honours-linked-issue-acceptance-criteria");
	try {
		const legacy = path.join(root, "inputs", "context");
		const context = path.join(root, "areas/linked work");
		const repo = path.join(root, "inputs", "sources", "scm", "repo");
		await mkdir(legacy, { recursive: true });
		await mkdir(context, { recursive: true });
		await writeFile(
			path.join(legacy, "linked_work_items.json"),
			JSON.stringify({ workItems: [{ body: "- [ ] WRONG CONTEXT" }] }),
		);
		await writeFile(
			path.join(context, "linked_work_items.json"),
			JSON.stringify({ workItems: [{ body: "Acceptance criteria\n- [ ] one\n- [ ] two" }] }),
		);
		const metadata = {
			source_branch: "fix-example",
			title: "Fixes #12",
			pr_number: 1,
			pr_url: "https://example.invalid/pull/1",
			repository_full_name: "owner/project",
			target_branch: "main",
			commit_sha: "a".repeat(40),
		};
		const explicit = await analyse(repo, new Map(), metadata, context);
		assert.equal(explicit.metrics.acceptanceCriteriaCheckboxes, 2);
		const absent = await analyse(repo, new Map(), metadata);
		assert.equal(absent.metrics.linkedItemsFilePresent, 0);
		const missing = await analyse(repo, new Map(), metadata, path.join(root, "missing"));
		assert.equal(missing.metrics.linkedItemsFilePresent, 0);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

void it("issue-reference syntax in templates remains a candidate rather than an authored closing claim", async () => {
	const metadata = {
		source_branch: "plain-branch",
		title: "Documentation update",
		body: "- [ ] Related issue is linked (e.g., `Closes #12`)\nFor the intended check, see #12.",
		pr_number: 1,
		pr_url: "https://example.invalid/pull/1",
		repository_full_name: "owner/project",
		target_branch: "main",
		commit_sha: "a".repeat(40),
	};
	const linked = await loadScript("honours-linked-issue-acceptance-criteria");
	const result = await linked("unused", new Map(), metadata);
	assert.equal(result.metrics.issueReferenceSyntaxCandidateCount, 1);
	assert.match(result.directions.join("\n"), /syntax candidate/u);
	assert.match(result.directions.join("\n"), /before establishing that the author/u);
	assert.doesNotMatch(result.directions.join("\n"), /this change claims to close/u);
	const traceable = await loadScript("ready-and-traceable-handoff");
	const trace = await traceable("unused", new Map(), metadata);
	assert.equal(trace.metrics.issueMentionSyntaxCandidateCount, 1);
	assert.match(
		trace.directions.join("\n"),
		/templates, examples and branch numbers may be unrelated/u,
	);
	assert.doesNotMatch(
		trace.directions.join("\n"),
		/all establish the link|motivating-issue reference IS present/u,
	);
});

void it("readiness precompute leaves every title to the model and keeps checklist and issue facts", async () => {
	const traceable = await loadScript("ready-and-traceable-handoff");
	for (const title of [
		"Add a saved message draft",
		"Enforce WIP limits on the board",
		"WIP: add the quiz results screen",
	]) {
		const trace = await traceable("unused", new Map(), {
			source_branch: "plain-branch",
			title,
			body: "Closes #42\n\n- [x] Results screen\n- [ ] Empty state\n",
			pr_number: 1,
			pr_url: "https://example.invalid/pull/1",
			repository_full_name: "owner/project",
			target_branch: "main",
			commit_sha: "a".repeat(40),
		});
		const directions = trace.directions.join("\n");
		assert.equal(trace.metrics.checklistTicked, 1);
		assert.equal(trace.metrics.checklistUnticked, 1);
		assert.equal(trace.metrics.issueMentionSyntaxCandidateCount, 1);
		assert.match(directions, /1 ticked and 1 unticked checkbox line/u);
		assert.match(directions, /#42/u);
		assert.doesNotMatch(directions, /draft-style|Readiness fact/u);
	}
});

void it("omits an uncaptured issue inventory count while preserving a captured empty listing", async () => {
	const run = await loadScript("issue-scoped-to-single-concern");
	const cases: [string | undefined, number | undefined][] = [
		[undefined, undefined],
		["{}", undefined],
		['{"issues":"unreadable"}', undefined],
		['{"issues":[]}', 0],
		['{"issues":[{"number":7,"title":"First screen"}]}', 1],
	];
	for (const [source, count] of cases) {
		const context = await createTempDir("pc-issue-inventory-");
		if (source !== undefined) {
			await writeFile(path.join(context, "project_inventory.json"), source);
		}
		const result = await run(
			"",
			new Map(),
			{ title: "First screen", body: "Show the signed-in name." },
			context,
		);
		assert.equal(Object.hasOwn(result.metrics, "siblingIssueCount"), count !== undefined);
		assert.equal(result.metrics.siblingIssueCount, count);
	}
});
