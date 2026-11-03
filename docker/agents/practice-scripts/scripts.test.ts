import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";

import type { DiffFile } from "./kits/scm/types.ts";
import { isJsonObject } from "./lib/core/json.ts";
import { isPracticeScriptDefinition, UnratedError } from "./lib/core/practice-script.ts";
import {
	event,
	requested,
	runBundled,
	runBundledProgram,
	runBundledReview,
	type RunInputs,
} from "./run-bundled.ts";
import { stagePracticeScripts } from "./stage.ts";

const KINDS = ["scm.pull_request", "scm.issue", "chat.thread", "docs.document"] as const;
type Kind = string;

const SCRIPTS_DIR = path.resolve(
	import.meta.dirname,
	"../../../server/application/src/main/resources/practices/scripts",
);

function changedFile(file: string): DiffFile {
	return { path: file, addedLines: new Map(), removedLines: new Map(), hunks: [] };
}

void describe("ships-tests-with-the-change", () => {
	const slug = "ships-tests-with-the-change";

	void it("classifies the changed source paths by convention, Xcode test target folders included", async () => {
		const paths = [
			"App/Latest.swift",
			"App/Contest.swift",
			"CourseUITests/Smoke.swift",
			"AppTests/ScoreTests.swift",
			"README.md",
		];
		const change = new Map(paths.map((file) => [file, changedFile(file)]));

		const result = await runBundled(slug, {
			repo: path.join(tmpdir(), `missing-checkout-${randomUUID()}`),
			change,
		});

		assert.deepEqual(result.facts, { diffProductionFiles: 2, diffTestFiles: 2 });
		assert.deepEqual(result.leads, []);
		assert.match(
			result.directions?.[0] ?? "",
			/^The diff changes 2 production source file\(s\) and 2 test file\(s\), by path: CourseUITests\/Smoke\.swift, AppTests\/ScoreTests\.swift\./u,
		);
	});

	void it("judges untested production code without a test target instead of abstaining", async () => {
		const change = new Map([["App/Quiz.swift", changedFile("App/Quiz.swift")]]);

		const result = await runBundled(slug, { change });

		assert.deepEqual(result.facts, { diffProductionFiles: 1, diffTestFiles: 0 });
		const directions = (result.directions ?? []).join(" ");
		assert.match(directions, /does not decide this practice: judge the changed behaviour/u);
		assert.doesNotMatch(directions, /cannot be assessed/u);
	});

	void it("says nothing about a change with no source file", async () => {
		const change = new Map([["README.md", changedFile("README.md")]]);

		const result = await runBundled(slug, { change });

		assert.deepEqual(result.directions, []);
	});

	void it("lists only as many test paths as one direction holds, and counts the rest", async () => {
		const paths = Array.from({ length: 30 }, (_, i) => `AppTests/Feature${String(i)}Tests.swift`);
		const change = new Map(paths.map((file) => [file, changedFile(file)]));

		const result = await runBundled(slug, { change });

		assert.equal(result.facts?.diffTestFiles, 30);
		assert.match(result.directions?.[0] ?? "", /, and \d+ more\.$/u);
	});
});

void describe("issue classification across practices", () => {
	const names = [
		"issue-has-checkable-outcome",
		"issue-states-an-actionable-problem",
		"issue-scoped-to-single-concern",
	];

	const opened = event("scm.issue.opened");

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
				const result = await runBundled(scriptName, { item: metadata, inputs: opened });
				assert.equal(result.facts?.emptyOrTitleEcho, emptyOrTitleEcho, scriptName);
				assert.deepEqual(result.leads, [], scriptName);
				if (scriptName !== "issue-scoped-to-single-concern") {
					assert.equal(result.facts.hasDeliverableType, hasDeliverableType, scriptName);
					assert.equal(result.facts.looksUmbrella, looksUmbrella, scriptName);
				}
				const said = (result.directions ?? []).join("\n");
				assert.equal(
					/body is empty|repeats the title and says nothing else/u.test(said),
					emptyOrTitleEcho === 1,
					`${scriptName}: ${name}`,
				);
				assert.doesNotMatch(said, /no quotable deliverable|nothing to build from/iu, scriptName);
			}
		});
	}

	const issue = { title: "Course checklist", body: "- [ ] Clarify\n- [ ] Reply\n- [ ] Defer" };
	const provenance = /as the provider reports|stored at capture|whole hierarchy/u;
	const subIssueCases: [
		Record<string, number | string | null>,
		RegExp,
		{ subIssuesTotal?: number; subIssuesCompleted?: number },
		RegExp,
	][] = [
		[{}, /sub-issue rollup not reported by the provider \(unknown, not zero\)/u, {}, provenance],
		[
			{ sub_issues_total: null, sub_issues_completed: null, sub_issues_source: "children" },
			/sub-issue rollup not reported by the provider \(unknown, not zero\)/u,
			{},
			provenance,
		],
		[
			{ sub_issues_total: 0, sub_issues_completed: 0 },
			/0 sub-issue\(s\), 0 completed/u,
			{ subIssuesTotal: 0, subIssuesCompleted: 0 },
			provenance,
		],
		[
			{ sub_issues_total: 3, sub_issues_completed: null },
			/3 sub-issue\(s\), completed count not reported/u,
			{ subIssuesTotal: 3 },
			provenance,
		],
		[
			{ sub_issues_total: 0, sub_issues_completed: 0, sub_issues_source: "provider" },
			/0 sub-issue\(s\), 0 completed, as the provider reports them/u,
			{ subIssuesTotal: 0, subIssuesCompleted: 0 },
			/stored at capture|whole hierarchy/u,
		],
		[
			{ sub_issues_total: 0, sub_issues_completed: 0, sub_issues_source: "children" },
			/0 sub-issue\(s\), 0 completed.*stored at capture/u,
			{ subIssuesTotal: 0, subIssuesCompleted: 0 },
			/as the provider reports|whole hierarchy|unknown/u,
		],
		[
			{ sub_issues_total: 2, sub_issues_completed: 2, sub_issues_source: "provider" },
			/2 sub-issue\(s\), 2 completed, as the provider reports them/u,
			{ subIssuesTotal: 2, subIssuesCompleted: 2 },
			/stored at capture|whole hierarchy/u,
		],
		[
			{ sub_issues_total: 3, sub_issues_completed: 1, sub_issues_source: "children" },
			/3 sub-issue\(s\), 1 completed.*stored at capture/u,
			{ subIssuesTotal: 3, subIssuesCompleted: 1 },
			/as the provider reports|whole hierarchy/u,
		],
		[
			{ sub_issues_total: 2, sub_issues_completed: 2, sub_issues_source: "children" },
			/2 sub-issue\(s\), 2 completed.*stored at capture/u,
			{ subIssuesTotal: 2, subIssuesCompleted: 2 },
			/as the provider reports/u,
		],
	];

	void it("refuses a rollup with an unrecognized source", async () => {
		await assert.rejects(
			runBundled("breaks-large-work-into-trackable-subtasks", {
				item: { ...issue, sub_issues_source: "Children" },
				inputs: opened,
			}),
			/sub_issues_source/u,
		);
	});

	void it("keeps sub-issue counts separate from their source and scope in each script that reads them", async () => {
		for (const { slug, checkboxes } of [
			{ slug: "breaks-large-work-into-trackable-subtasks", checkboxes: "taskCheckboxes" },
			{ slug: "issue-scoped-to-single-concern", checkboxes: "checkboxes" },
		]) {
			for (const [rollup, text, facts, unsaid] of subIssueCases) {
				const result = await runBundled(slug, { item: { ...issue, ...rollup }, inputs: opened });
				const said = (result.directions ?? []).join("\n");
				assert.deepEqual(result.leads, [], slug);
				assert.match(said, text, slug);
				assert.doesNotMatch(said, unsaid, slug);
				if (
					rollup.sub_issues_source === "children" &&
					typeof rollup.sub_issues_total === "number"
				) {
					assert.match(said, /mirror may omit other children/u, slug);
					if (
						rollup.sub_issues_total > 0 &&
						rollup.sub_issues_completed === rollup.sub_issues_total
					) {
						assert.match(
							said,
							/stored children were closed, not.*whole hierarchy.*complete/u,
							slug,
						);
					}
				}
				assert.equal(result.facts?.subIssuesTotal, facts.subIssuesTotal, slug);
				assert.equal(result.facts?.subIssuesCompleted, facts.subIssuesCompleted, slug);
				assert.equal(result.facts?.[checkboxes], 3, slug);
				assert.doesNotMatch(said, /looks large|strong candidate/iu, slug);
			}
		}
	});
});

void it("testing guidance keeps words separated by template comments apart", async () => {
	const result = await runBundled("states-how-to-verify-the-change", {
		item: { title: "", source_branch: "", body: "## How to test\nn<!-- template -->a" },
	});
	assert.match((result.directions ?? []).join("\n"), /holds 1 line\(s\) of author text/u);
});

void it("linked-work analysis reads only explicitly supplied context, never a repository-derived fallback", async () => {
	const root = await mkdtemp(path.join(tmpdir(), "linked-work-context-"));
	const slug = "honours-linked-issue-acceptance-criteria";
	try {
		const legacy = path.join(root, "inputs", "context");
		const context = path.join(root, "areas/linked work");
		const repo = path.join(root, "inputs", "sources", "scm", "repo");
		await mkdir(legacy, { recursive: true });
		await mkdir(context, { recursive: true });
		await writeFile(
			path.join(legacy, "linked_work_items.json"),
			JSON.stringify({
				workItems: [{ number: 12, title: "Wrong issue", body: "- [ ] WRONG CONTEXT" }],
			}),
		);
		await writeFile(
			path.join(context, "linked_work_items.json"),
			JSON.stringify({
				workItems: [
					{ number: 12, title: "Issue", body: "Acceptance criteria\n- [ ] one\n- [ ] two" },
				],
			}),
		);
		const item = { source_branch: "fix-example", title: "Fixes #12" };
		const explicit = await runBundled(slug, { item, repo, context });
		assert.equal(explicit.facts?.acceptanceCriteriaCheckboxes, 2);
		assert.deepEqual(explicit.leads, []);
		const absent = await runBundled(slug, { item, repo });
		assert.equal(absent.facts?.linkedItemsFilePresent, 0);
		const missing = await runBundled(slug, { item, repo, context: path.join(root, "missing") });
		assert.equal(missing.facts?.linkedItemsFilePresent, 0);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

void it("linked acceptance criteria require the captured workItems record", async () => {
	const slug = "honours-linked-issue-acceptance-criteria";
	for (const record of [
		{},
		[],
		{ items: [] },
		{ number: 12, title: "Issue", body: "- [ ] task" },
		{ workItems: [null] },
		{ workItems: [{ number: 12, title: "Issue", description: "- [ ] task" }] },
	]) {
		await assert.rejects(
			runBundled(slug, { context: { "linked_work_items.json": record } }),
			(error: unknown) => {
				assert.ok(error instanceof UnratedError);
				assert.equal(error.reason, "unavailable");
				assert.match(error.message, /linked_work_items\.json/u);
				return true;
			},
		);
	}
	const result = await runBundled(slug, {
		context: {
			"linked_work_items.json": { workItems: [{ number: 12, title: "Issue", body: null }] },
		},
	});
	assert.equal(result.facts?.linkedIssueCount, 1);
	assert.equal(result.facts.linkedIssueBodyPresent, 0);
});

void it("a logger under a long repository path stays within the review direction bound", async () => {
	const file = "App/main.ts";
	const logger = `${"folder/".repeat(60)}logger.ts`;
	const source = 'console.log("diagnostic");';
	const result = await runBundled("logs-through-the-platform-logger", {
		repo: { [file]: source, [logger]: 'const log = LoggerFactory.getLogger("App");' },
		change: new Map([
			[
				file,
				{ path: file, addedLines: new Map([[1, source]]), removedLines: new Map(), hunks: [] },
			],
		]),
	});
	assert.equal(result.leads?.length, 1);
	const direction = result.directions?.find((text) =>
		text.includes("the checkout matches a logger"),
	);
	assert.ok(direction !== undefined);
	assert.equal(direction.length, 300);
	assert.ok(direction.endsWith("…"));
});

void it("quoted closing syntax does not become an authored closing claim", async () => {
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
	const result = await runBundled("honours-linked-issue-acceptance-criteria", { item: metadata });
	const said = (result.directions ?? []).join("\n");
	assert.equal(result.facts?.issueReferenceSyntaxCandidateCount, 0);
	assert.match(said, /No closing reference/u);
	assert.match(said, /confirm there is genuinely no linked tracker issue/u);
	assert.doesNotMatch(said, /this change claims to close/u);
	const trace = await runBundled("ready-and-traceable-handoff", { item: metadata });
	assert.equal(trace.facts?.issueMentionSyntaxCandidateCount, 1);
	assert.match(
		(trace.directions ?? []).join("\n"),
		/templates, examples and branch numbers may be unrelated/u,
	);
	assert.doesNotMatch(
		(trace.directions ?? []).join("\n"),
		/all establish the link|motivating-issue reference IS present/u,
	);
});

void it("the readiness practice script leaves every title to the model and keeps checklist and issue facts", async () => {
	for (const title of [
		"Add a saved message draft",
		"Enforce WIP limits on the board",
		"WIP: add the quiz results screen",
	]) {
		const trace = await runBundled("ready-and-traceable-handoff", {
			item: {
				source_branch: "plain-branch",
				title,
				body: "Closes #42\n\n- [x] Results screen\n- [ ] Empty state\n",
			},
		});
		const directions = (trace.directions ?? []).join("\n");
		assert.equal(trace.facts?.checklistTicked, 1);
		assert.equal(trace.facts.checklistUnticked, 1);
		assert.equal(trace.facts.issueMentionSyntaxCandidateCount, 1);
		assert.match(directions, /1 ticked and 1 unticked checkbox line/u);
		assert.match(directions, /#42/u);
		assert.doesNotMatch(directions, /draft-style|Readiness fact/u);
	}
});

const MINIMAL_INPUTS: Record<Kind, RunInputs> = {
	"scm.pull_request": { inputs: requested("scm.pull_request"), change: new Map() },
	"scm.issue": { inputs: requested("scm.issue") },
	"chat.conversation_thread": {
		inputs: requested("chat.conversation_thread"),
		item: { channel: "C1", threadTs: "1.0", messages: [], messageCount: 0, truncated: false },
	},
	"docs.document": {
		inputs: requested("docs.document"),
		item: {
			title: null,
			collection: null,
			collectionSlug: null,
			slug: null,
			createdBy: null,
			updatedBy: null,
			createdAt: null,
			updatedAt: null,
			archived: false,
		},
	},
};

async function kindOf(slug: string): Promise<Kind> {
	const { outcome } = await runBundledProgram(slug, MINIMAL_INPUTS["scm.pull_request"]);
	const message = "error" in outcome && outcome.error instanceof Error ? outcome.error.message : "";
	const read = /^the script reads (?<kind>\S+),/u.exec(message)?.groups?.kind;
	return KINDS.find((kind) => kind === read) ?? "scm.pull_request";
}

async function stagedMeta(slug: string): Promise<unknown> {
	const source = await readFile(path.join(SCRIPTS_DIR, `${slug}.ts`), "utf8");
	const { root, practices } = await stagePracticeScripts({ [slug]: source }, {});
	try {
		const mod: unknown = await import(path.join(practices, `${slug}.ts`));
		const definition: unknown = isJsonObject(mod) ? mod.default : undefined;
		assert.ok(isPracticeScriptDefinition(definition), `${slug} exports no definition`);
		return definition.meta;
	} finally {
		await rm(root, { recursive: true, force: true });
	}
}

void describe("every bundled practice script", async () => {
	const names = await readdir(SCRIPTS_DIR);
	const slugs = names
		.filter((name) => name.endsWith(".ts"))
		.map((name) => name.slice(0, -".ts".length))
		.toSorted();

	for (const slug of slugs) {
		void it(`${slug} asks exactly one review on minimal inputs of its kind`, async () => {
			await runBundledReview(slug, MINIMAL_INPUTS[await kindOf(slug)]);
		});

		void it(`${slug} declares an empty meta: it needs no direct model`, async () => {
			assert.deepEqual(await stagedMeta(slug), {});
		});
	}

	void it("a change that was not captured stops a program that reads the change before any review", async () => {
		const { reviews, outcome } = await runBundledProgram("ships-tests-with-the-change", {
			change: null,
		});
		assert.equal(reviews.length, 0);
		assert.ok("error" in outcome);
		assert.ok(outcome.error instanceof Error);
		assert.equal(outcome.error.name, "UnratedError");
	});
});

void describe("migrated authored practice scripts", () => {
	void it("carries routing metadata without deciding its accuracy or taxonomy", async () => {
		const result = await runBundled("triages-the-issue-with-labels-and-ownership", {
			inputs: requested("scm.issue"),
			item: { labels: ["ui", "help wanted"], assignees: ["maintainer"], milestone: "October" },
		});
		assert.deepEqual(result.facts, { labelCount: 2, assigneeCount: 1, hasMilestone: 1 });
		const said = result.directions?.join(" ") ?? "";
		assert.match(said, /ui, help wanted.*maintainer.*October/u);
		assert.match(said, /not proof that a label is useful or correct/u);
	});

	void it("does not review absent routing fields as an empty captured set", async () => {
		const result = await runBundledProgram("triages-the-issue-with-labels-and-ownership", {
			inputs: requested("scm.issue"),
			item: { labels: undefined },
		});
		assert.equal(result.reviews.length, 0);
		assert.ok("error" in result.outcome);
	});

	void it("keeps closing disposition, checklist and stored-child scope visible without dating them", async () => {
		const result = await runBundled("issue-closed-with-unmet-outcome", {
			inputs: requested("scm.issue"),
			item: {
				state: "CLOSED",
				state_reason: "not_planned",
				closed_at: "2026-10-12T10:00:00Z",
				body: "- [x] Explain\n- [ ] Follow up",
				sub_issues_total: 2,
				sub_issues_completed: 2,
				sub_issues_source: "children",
			},
		});
		assert.deepEqual(result.facts, {
			issueClosed: 1,
			checkedBoxes: 1,
			uncheckedBoxes: 1,
			subIssuesTotal: 2,
			subIssuesCompleted: 2,
		});
		const said = result.directions?.join(" ") ?? "";
		assert.match(said, /not_planned/u);
		assert.match(said, /stored at capture.*may omit other children/u);
		assert.match(said, /not necessarily the moment of closing/u);
	});

	void it("keeps unknown closure and rollup apart from explicit open state and reported zero", async () => {
		for (const known of [false, true]) {
			const result = await runBundled("issue-closed-with-unmet-outcome", {
				inputs: requested("scm.issue"),
				item: {
					state: known ? "OPEN" : "UNKNOWN",
					sub_issues_total: known ? 0 : null,
					sub_issues_completed: known ? 0 : null,
					...(known ? { sub_issues_source: "provider" } : {}),
				},
			});
			assert.equal(result.facts?.issueClosed, known ? 0 : undefined);
			assert.equal(result.facts?.subIssuesTotal, known ? 0 : undefined);
			assert.match(
				result.directions?.join(" ") ?? "",
				known ? /0 sub-issue.*provider reports/u : /unknown, not zero/u,
			);
		}
	});
});

/** A change that adds every line of each file. */
function addedSources(files: Record<string, string>): RunInputs {
	return {
		repo: files,
		change: new Map(
			Object.entries(files).map(([file, text]) => [
				file,
				{
					path: file,
					addedLines: new Map(text.split("\n").map((line, index) => [index + 1, line])),
					removedLines: new Map(),
					hunks: [],
				},
			]),
		),
	};
}

function complexView(previews: string): string {
	return `struct Screen: View {\n@State private var a = 0\n@State private var b = 0\n@State private var c = 0\n@State private var d = 0\nvar body: some View { switch a { default: Text("") } }\n}\n${previews}`;
}

function commit(message: string, index: number) {
	return { sha: String(index).repeat(40), message, parents: [], files: [] };
}

const STATE_SOURCE = [
	"final class Store: ObservableObject {",
	"@Published var items: [String] = []",
	"}",
	"struct Screen: View {",
	"@ObservedObject var store = Store()",
	"@State private var count = 0 {",
	"didSet { print(count) }",
	"}",
	'var body: some View { Text("") }',
	"@State var selection: Store",
	"}",
	"#Preview { @State var preview = 1 }",
].join("\n");

void describe("migrated workspace practice scripts", () => {
	void it("names complex changed views by their preview count", async () => {
		const result = await runBundled(
			"covers-complex-views-with-previews",
			addedSources({
				"App/None.swift": complexView(""),
				"App/One.swift": complexView("#Preview { Screen() }"),
				"App/Two.swift": complexView('#Preview { Screen() }\n#Preview("Empty") { Screen() }'),
				"App/Plain.swift": 'struct Plain: View {\nvar body: some View { Text("") }\n}',
			}),
		);
		assert.deepEqual(result.facts, {
			viewFiles: 4,
			complexWithoutPreview: 1,
			complexWithOnePreview: 1,
		});
		assert.deepEqual(
			result.leads?.map((lead) => [
				"file" in lead.at ? lead.at.file : "",
				lead.facts?.previewBlocks,
			]),
			[
				["App/None.swift", 0],
				["App/One.swift", 1],
			],
		);
	});

	void it("reads state ownership cues on changed lines only", async () => {
		const result = await runBundled(
			"gives-swiftui-state-one-clear-owner",
			addedSources({ "App/Screen.swift": STATE_SOURCE }),
		);
		assert.deepEqual(result.facts, {
			observableObject: 1,
			published: 1,
			observedObjectCreates: 1,
			stateObserver: 1,
			stateNotPrivate: 1,
			stateWithoutDefault: 1,
			stateReferenceType: 1,
		});
		const said = result.directions?.join(" ") ?? "";
		assert.match(said, /recreated whenever the parent rebuilds/u);
		assert.match(said, /keeps its first value/u);

		const unchanged = await runBundled("gives-swiftui-state-one-clear-owner", {
			repo: { "App/Screen.swift": STATE_SOURCE },
			change: new Map([
				[
					"App/Screen.swift",
					{
						path: "App/Screen.swift",
						addedLines: new Map([[9, 'var body: some View { Text("") }']]),
						removedLines: new Map(),
						hunks: [],
					},
				],
			]),
		});
		assert.deepEqual(unchanged.facts, {});
	});

	void it("marks credential-shaped lines without repeating their values", async () => {
		const key = ["AKIA", "IOSFODNN7EXAMPLE"].join("");
		const assigned = ["q8Zr2LmX", "9vTn4KpW"].join("");
		const result = await runBundled(
			"keeps-credentials-out-of-source",
			addedSources({
				"App/Keys.swift": `let id = "${key}"\nlet apiKey = "${assigned}"\nlet token = "YOUR_TOKEN_HERE_123"`,
				"logo.png": "\u0000binary",
			}),
		);
		assert.deepEqual(result.facts, { knownPrefix: 1, secretAssignment: 1 });
		assert.deepEqual(
			result.leads?.map((lead) => lead.at.line),
			[1, 2],
		);
		const text = JSON.stringify(result);
		assert.ok(!text.includes(key) && !text.includes(assigned));
		assert.match(result.directions?.join(" ") ?? "", /Never repeat a suspected value/u);
	});

	void it("reads a source file in each selected language and each configuration file type it names, and no unnamed file type", async () => {
		const line = 'database = "postgres://app:fixture@db/app"';
		const read = [
			..."plist json yml yaml xcconfig env properties toml xml ini conf cfg sh"
				.split(" ")
				.map((extension) => `config/app.${extension}`),
			"src/Db.kt",
			"src/db.go",
			"src/db.php",
			"src/db.py",
			"src/db.rb",
		];
		const unread = ["docs/db.md", "notes/db.txt", "Dockerfile"];
		const result = await runBundled(
			"keeps-credentials-out-of-source",
			addedSources(Object.fromEntries([...read, ...unread].map((file) => [file, line]))),
		);
		assert.deepEqual(
			(result.leads ?? []).map((lead) => ("file" in lead.at ? lead.at.file : "")).toSorted(),
			read.toSorted(),
		);
		assert.deepEqual(result.facts, { connectionString: read.length });
		assert.match(
			result.directions?.join(" ") ?? "",
			new RegExp(`Scanned the added lines of ${String(read.length)} changed`, "u"),
		);
	});

	void it("measures long changed view bodies and a repeated refresh", async () => {
		const filler = Array.from({ length: 52 }, () => 'Text("row")').join("\n");
		const result = await runBundled(
			"keeps-swiftui-view-bodies-small",
			addedSources({
				"App/Long.swift": `struct Long: View {\nvar body: some View {\nVStack {\n${filler}\n}\n.task { await model.load(page: 1) }\n.refreshable { await model.load(page: 1) }\n}\n}`,
				"App/Short.swift": 'struct Short: View {\nvar body: some View {\nText("")\n}\n}',
			}),
		);
		assert.equal(result.facts?.viewBodies, 1);
		assert.equal(result.facts.bodiesOver50Lines, 1);
		assert.equal(result.facts.bodiesOver80Lines, 0);
		assert.equal(result.facts.sameTaskAndRefresh, 1);
		assert.equal(result.leads?.length, 1);
		assert.equal(result.leads[0]?.facts?.bodyLines, 56);
	});

	void it("finds leftover output and commented code but leaves catch-block prints alone", async () => {
		const result = await runBundled(
			"removes-debug-output-and-leftover-code",
			addedSources({
				"App/Load.swift": [
					'print("loaded")',
					"// let old = 1",
					"// return old",
					'NSLog("x")',
					"// TODO: tidy",
					"",
					"do {",
					"try run()",
					"} catch {",
					"print(error)",
					"}",
				].join("\n"),
			}),
		);
		assert.deepEqual(result.facts, { print: 1, commentedCode: 1, nslog: 1, todo: 1 });
	});

	void it("names identifiers that break the Swift naming conventions", async () => {
		const result = await runBundled(
			"names-swift-identifiers-by-convention",
			addedSources({
				"App/Profile.swift":
					'let user_name = ""\nlet URLPath = ""\nvar nameString = ""\nstruct Account {}',
				"AppTests/Profile.swift": 'let user_name = ""',
			}),
		);
		assert.deepEqual(result.facts, {
			snakeCaseProperty: 1,
			acronymCasing: 1,
			typeInName: 1,
			fileTypeMismatch: 1,
		});
	});

	void it("tells which loading and error states a fetching view has", async () => {
		const result = await runBundled(
			"shows-loading-and-error-states-for-fetched-data",
			addedSources({
				"App/Feed.swift":
					"struct Feed: View {\nvar body: some View { ProgressView() }\nfunc load() async { _ = try? await URLSession.shared.data(from: url) }\n}",
				"App/Static.swift": 'struct Static: View {\nvar body: some View { Text("") }\n}',
			}),
		);
		assert.deepEqual(result.facts, {
			fetchingViews: 1,
			withoutLoadingOrError: 0,
			withoutLoading: 0,
			withoutError: 1,
			spinnerWithoutError: 1,
			rawErrorText: 0,
		});
		assert.deepEqual(result.leads?.[0]?.facts, {
			source: "URLSession",
			hasLoadingState: true,
			hasErrorState: false,
			hasProgressView: true,
		});
	});

	void it("separates the description's own words from its template", async () => {
		const checklist = await runBundled("describes-the-change-beyond-the-template", {
			item: {
				title: "Add export",
				body: "## Description\n- [ ] Tests\n- [x] Docs\nCloses #12",
				changed_files: 8,
			},
			context: { "commits.json": { commits: [] } },
		});
		assert.equal(checklist.facts?.checklistOnly, true);
		assert.equal(checklist.facts.emptyBody, undefined);
		assert.equal(checklist.facts.descriptionWords, 0);
		assert.equal(checklist.facts.checkboxes, 2);

		const empty = await runBundled("describes-the-change-beyond-the-template", {
			item: { title: "update", body: "", changed_files: 1 },
		});
		assert.equal(empty.facts?.emptyBody, true);
		assert.equal(empty.facts.genericTitle, true);
		const said = empty.directions?.join(" ") ?? "";
		assert.match(said, /^The description is empty\. The title is a generic word/u);
		assert.match(said, /was not captured/u);
	});

	void it("keeps an uncaptured title and description unknown, apart from a captured empty one", async () => {
		const context = { "commits.json": { commits: [commit("Add CSV export", 1)] } };
		const omitted = await runBundled("describes-the-change-beyond-the-template", {
			item: { title: undefined, body: undefined, changed_files: 8 },
			context,
		});
		assert.deepEqual(omitted.facts, { changedFiles: 8, capturedCommits: 1 });
		assert.match(
			omitted.directions?.[0] ?? "",
			/^Not captured, so unknown rather than empty: description, title\./u,
		);
		const omittedSaid = omitted.directions?.join(" ") ?? "";
		assert.match(
			omittedSaid,
			/description was not captured, so it was not compared with the 1 captured/u,
		);
		assert.doesNotMatch(omittedSaid, /is compared with/u);

		const known = await runBundled("describes-the-change-beyond-the-template", {
			item: { title: "update", body: null, changed_files: 8 },
			context,
		});
		assert.equal(known.facts?.emptyBody, true);
		assert.equal(known.facts.genericTitle, true);
		assert.equal(known.facts.descriptionWords, 0);
		const knownSaid = known.directions?.join(" ") ?? "";
		assert.doesNotMatch(knownSaid, /Not captured|was not compared/u);
		assert.match(knownSaid, /is compared with the 1 captured commit subject/u);

		const untitled = await runBundled("describes-the-change-beyond-the-template", {
			item: {
				title: undefined,
				body: "Exports the report table as CSV for finance.",
				changed_files: 2,
			},
			context,
		});
		assert.equal(untitled.facts?.descriptionWords, 8);
		assert.equal(untitled.facts.bodyRepeatsTitle, undefined);
		assert.match(untitled.directions?.[0] ?? "", /unknown rather than empty: title\./u);
	});

	void it("reads commit subjects when the title was not captured", async () => {
		const result = await runBundled("titles-commits-and-requests-by-their-change", {
			item: { title: undefined },
			context: { "commits.json": { commits: ["wip", "Add CSV export"].map(commit) } },
		});
		assert.ok(result.facts !== undefined);
		assert.equal(result.facts.titleLength, undefined);
		assert.equal(result.facts.titleCues, undefined);
		assert.equal(result.facts.authoredCommits, 2);
		assert.equal(result.facts.vagueSubjects, 1);
		assert.match(
			result.directions?.[0] ?? "",
			/^The title was not captured, so it is unknown, not empty/u,
		);
	});

	void it("reads what a changed catch block and an optional try do with the failure", async () => {
		const result = await runBundled(
			"surfaces-failures-instead-of-silencing-them",
			addedSources({
				"App/Store.swift": [
					"func load() {",
					"do {",
					"try run()",
					"} catch {",
					"}",
					"do {",
					"try run()",
					"} catch {",
					"errorMessage = error.localizedDescription",
					"}",
					"do {",
					"try run()",
					"} catch {",
					"Logger().error(error)",
					"cleanUp()",
					"retry()",
					"}",
					"let data = try? await URLSession.shared.data(from: url)",
					"}",
					"func save() {",
					"}",
				].join("\n"),
			}),
		);
		assert.deepEqual(result.facts, {
			emptyCatch: 1,
			optionalTryNetwork: 1,
			emptyStatefulFunction: 1,
		});
	});

	void it("finds I/O on changed lines of view files only", async () => {
		const result = await runBundled(
			"keeps-io-and-domain-logic-out-of-views",
			addedSources({
				"App/Feed.swift": [
					"struct Feed: View {",
					'var body: some View { Text("") }',
					"func load() async throws {",
					"let (data, _) = try await URLSession.shared.data(from: url)",
					"let item = try JSONDecoder().decode(Item.self, from: data)",
					'UserDefaults.standard.set(item.id, forKey: "last")',
					"}",
					"}",
				].join("\n"),
				"App/Client.swift": "final class Client {\nlet session = URLSession.shared\n}",
			}),
		);
		assert.deepEqual(result.facts, { networking: 1, jsonCoding: 1, userDefaults: 1 });
		assert.equal(result.leads?.[0]?.facts?.view, "Feed");
	});

	void it("lists crash-prone shortcuts and marks those in tests", async () => {
		const source = [
			'let value = dict["a"]!',
			"let loaded = try! load()",
			'fatalError("unreachable")',
			"let head = items[0]",
			"let cell = view as! Cell",
		].join("\n");
		const result = await runBundled(
			"avoids-crash-prone-swift-shortcuts",
			addedSources({
				"App/Store.swift": source,
				"AppTests/StoreTests.swift": "let loaded = try! load()",
			}),
		);
		assert.deepEqual(result.facts, {
			forceUnwrap: 1,
			forcedTry: 2,
			fatalError: 1,
			firstElementIndex: 1,
			forcedCast: 1,
			inPreviewOrTest: 1,
		});
	});

	void it("finds unlabelled icons, fixed font sizes and silent tap targets", async () => {
		const result = await runBundled(
			"labels-images-and-taps-for-assistive-technology",
			addedSources({
				"App/Icon.swift":
					'struct Icon: View {\nvar body: some View { Image(systemName: "star") }\n}',
				"App/Row.swift":
					'struct Row: View {\nvar body: some View { Text("Tap").font(.system(size: 14)).onTapGesture { } }\n}',
				"App/Labelled.swift":
					'struct Labelled: View {\nvar body: some View { Image(systemName: "gear")\n.accessibilityLabel("Settings") }\n}',
			}),
		);
		assert.deepEqual(result.facts, { unlabeledImage: 1, fixedFontSize: 1, tapWithoutTraits: 1 });
	});

	void it("reads title and commit subjects, and does not read an uncaptured range as clean", async () => {
		const result = await runBundled("titles-commits-and-requests-by-their-change", {
			item: { title: "update", source_branch: "feature/export-csv" },
			context: {
				"commits.json": {
					commits: ["wip", "Add CSV export for reports", "wip"].map(commit),
				},
			},
		});
		assert.equal(result.facts?.authoredCommits, 3);
		assert.equal(result.facts.vagueSubjects, 2);
		assert.equal(result.facts.repeatedSubjects, 1);
		assert.equal(result.facts.titleCues, 1);
		assert.deepEqual(
			result.leads?.map((lead) => lead.facts?.category),
			["meaningless", "meaningless"],
		);
		const said = result.directions?.join(" ") ?? "";
		assert.match(said, /^The title is a verb with no subject/u);
		assert.match(said, /Half or more/u);

		const uncaptured = await runBundled("titles-commits-and-requests-by-their-change", {
			item: { title: "Export reports as CSV" },
		});
		assert.equal(uncaptured.facts?.authoredCommits, undefined);
		assert.match(uncaptured.directions?.join(" ") ?? "", /unknown, not clean/u);
	});
});
