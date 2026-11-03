import assert from "node:assert/strict";
import test from "node:test";

import {
	readCapturedCommits,
	type ChangedFile,
} from "../../../../../../docker/agents/practice-scripts/kits/scm/change.ts";
import { subjectFacts } from "../../../../../../docker/agents/practice-scripts/kits/scm/commit-subjects.ts";
import {
	leadKeys,
	runBundled,
} from "../../../../../../docker/agents/practice-scripts/run-bundled.ts";

function evidence(source?: string) {
	return {
		record: async (name: string) =>
			name === "commits.json" && source !== undefined
				? { status: "FOUND" as const, text: source }
				: { status: "ABSENT" as const },
	};
}

void test("captured commits distinguish empty history from missing or malformed records", async () => {
	assert.equal(await readCapturedCommits(evidence()), null);
	for (const source of ["{", '{"commits":[null]}', '{"commits":{}}', '{"commits":[{}]}']) {
		await assert.rejects(readCapturedCommits(evidence(source)), { name: "UnratedError" });
	}
	assert.deepEqual(await readCapturedCommits(evidence('{"commits":[]}')), []);
	const captured = await readCapturedCommits(
		evidence(
			JSON.stringify({
				commits: [
					{ sha: "1234567".padEnd(40, "a"), message: "", parents: [], files: [], authoredAt: "" },
				],
			}),
		),
	);
	assert.equal(captured?.[0]?.authoredAt, "");
});

const commit = (sha: string, message: string, parents = ["p"], files: ChangedFile[] = []) => ({
	sha: sha.padEnd(40, "a"),
	message,
	author: "a",
	authoredAt: "",
	committer: "a",
	committedAt: "",
	parents: parents.map((_, i) => String(i + 1).repeat(40)),
	files,
	line: 0,
});

void test("a subject's shape is a fact — bare, repeated, listed, cut off — and a merge is set aside", () => {
	const facts = subjectFacts([
		commit("1111111", "Add button to start run\n"),
		commit("2222222", "fix\n"),
		commit("3333333", "Add button to start run\n"),
		commit("4444444", "add location manager, SwiftData persistence, and UI color cleanup\n"),
		commit("5555555", "Add support for\n"),
		commit("6666666", "Merge branch 'main' into feature\n", ["p1", "p2"]),
		commit(
			"7777777",
			"Refactor ProfileViewModel\n\n- remove stored context\n- add logging\n- add enum\n",
		),
		commit("8888888", "update readme\n"),
		commit("9999999", "Show loading indicator. Implement feedback from review\n"),
	]);
	const by = Object.fromEntries(facts.map((f) => [f.sha.slice(0, 7), f]));
	assert.deepEqual(
		[by["1111111"]?.bare, by["2222222"]?.bare, by["8888888"]?.bare],
		[false, true, false],
	);
	assert.deepEqual(
		[by["1111111"]?.repeat, by["3333333"]?.repeat, by["2222222"]?.repeat],
		[true, true, false],
	);
	assert.equal(by["4444444"]?.joinedClauses, true);
	assert.equal(by["7777777"]?.joinedClauses, true);
	assert.equal(by["1111111"]?.joinedClauses, false);
	assert.equal(by["9999999"]?.joinedClauses, true);
	assert.equal(by["5555555"]?.cutOff, true);
	assert.equal(by["6666666"]?.merge, true);
});

async function runOnCommitRecord(slug: string, record: unknown) {
	const result = await runBundled(slug, {
		context: { "commits.json": record },
		contextReference: "areas/changed-item",
	});
	return { ...result, leads: result.leads ?? [] };
}

void test("both commit practices read the subjects from the commit record and state shapes, not verdicts", async () => {
	const commits = [
		commit("1111111", "Add button to start run\n"),
		commit("2222222", "add location manager, SwiftData persistence, and UI color cleanup\n"),
		commit("3333333", "Merge branch 'main'\n", ["p1", "p2"]),
	];
	for (const slug of ["commit-subjects-explain-each-change", "commits-are-atomic-and-cohesive"]) {
		const keyOf = await leadKeys(slug);
		const result = await runOnCommitRecord(slug, { commits });
		assert.equal(result.facts?.authoredCommits, 2);
		assert.equal(result.facts.mergeCommits, 1);
		assert.match(
			result.directions?.[0] ?? "",
			/2 authored commit\(s\), 1 merge commit\(s\) excluded/u,
		);
		assert.deepEqual(
			result.leads.map((lead) => [lead.at, keyOf(lead.label), lead.facts?.sha]),
			[
				[{ record: "commits.json" }, "commit", "1111111"],
				[{ record: "commits.json" }, "commit", "2222222"],
			],
		);
	}
});

void test("a terse subject that names its object is not a shape to check, whatever files its commit touched", async () => {
	const image: ChangedFile = { status: "M", path: "docs/screenshot.png" };
	const result = await runOnCommitRecord("commit-subjects-explain-each-change", {
		commits: [
			commit("1111111", "Update Readme\n", ["p"], [image]),
			commit("2222222", "update\n", ["p"], [image]),
		],
	});
	assert.deepEqual(
		result.leads.map((lead) => [lead.facts?.sha, lead.facts?.bare]),
		[
			["1111111", false],
			["2222222", true],
		],
	);
	assert.equal("paths" in (result.leads[0]?.facts ?? {}), false);
	assert.doesNotMatch(result.directions?.[1] ?? "", /about the files it touched/u);
});

void test("subject leads keep informative conjunctions and vague subjects separate from commit scope", async () => {
	const result = await runOnCommitRecord("commit-subjects-explain-each-change", {
		commits: [
			commit(
				"1111111",
				"Restore request failures and remove the HTTP status check",
				["p"],
				[{ status: "M", path: "App/Model.swift", additions: 2, deletions: 8 }],
			),
			commit("2222222", "fix"),
			commit("3333333", "Merge branch 'main'", ["p1", "p2"]),
		],
	});
	assert.equal(result.facts?.authoredCommits, 2);
	assert.equal(result.facts.mergeCommits, 1);
	assert.deepEqual(
		result.leads.map((lead) => lead.facts),
		[
			{ sha: "1111111", bare: false, repeat: false, cutOff: false },
			{ sha: "2222222", bare: true, repeat: false, cutOff: false },
		],
	);
});

void test("one authored commit is still a history to judge, and an unread commit record is a collection gap", async () => {
	for (const slug of ["commit-subjects-explain-each-change", "commits-are-atomic-and-cohesive"]) {
		const single = await runOnCommitRecord(slug, {
			commits: [commit("1111111", "Add the quiz view and reformat the project\n")],
		});
		assert.equal(single.facts?.authoredCommits, 1);
		assert.equal(single.leads.length, 1);
		assert.match(single.directions?.[0] ?? "", /^1 authored commit\(s\), one lead each/u);
		assert.doesNotMatch((single.directions ?? []).join(" "), /no partition|nothing to judge/u);
		for (const source of [
			"{",
			'{"commits":[null]}',
			'{"commits":[{}]}',
			'{"commits":[{"sha":"1234567","message":"","parents":"missing","files":[]}]}',
			'{"commits":[{"sha":"1234567","message":"","parents":[],"files":[{}]}]}',
		]) {
			await assert.rejects(runOnCommitRecord(slug, source), { name: "UnratedError" });
		}
		const empty = await runOnCommitRecord(slug, '{"commits":[]}');
		assert.match(empty.directions?.[0] ?? "", /^No authored commit in the reviewed range/u);
		assert.equal(empty.facts?.authoredCommits, 0);
		assert.equal(empty.facts.mergeCommits, 0);
		const merges = await runOnCommitRecord(slug, {
			commits: [commit("3333333", "Merge branch 'main'", ["p1", "p2"])],
		});
		assert.deepEqual(merges.leads, []);
		assert.equal(merges.facts?.authoredCommits, 0);
		assert.equal(merges.facts.mergeCommits, 1);
	}
});

const renamed = (name: string) => ({
	status: "R",
	oldPath: `IntrocourseApp/${name}`,
	path: `PillApp/${name}`,
	additions: 0,
	deletions: 0,
});

void test("a commit lead lists the edited files with their line counts before the moves, and names no kinds", async () => {
	const commits = [
		commit(
			"1111111",
			"Create problem statement\n",
			["p"],
			[
				...["Assets.xcassets/Contents.json", "Info.plist", "ContentView.swift"].map(renamed),
				...["AccentColor.colorset/Contents.json", "AppIcon.appiconset/Contents.json"].map(renamed),
				renamed("PillApp.swift"),
				{ status: "A", path: "PillApp/WelcomeView.swift", additions: 18, deletions: 0 },
				{ status: "M", path: "README.md", additions: 20, deletions: 5 },
			],
		),
		commit(
			"2222222",
			"Add order history, and adjust invoice loading\n",
			["p"],
			[
				{ status: "M", path: "Orders/HistoryViewModel.swift", additions: 40, deletions: 2 },
				{ status: "M", path: "project.yml", additions: 3, deletions: 0 },
				{
					status: "R",
					oldPath: "Orders/InvoiceResponse.swift",
					path: "Orders/InvoiceListResponse.swift",
					additions: 3,
					deletions: 3,
				},
			],
		),
	];
	const result = await runOnCommitRecord("commits-are-atomic-and-cohesive", { commits });
	const [readme, feature] = result.leads.map((lead) => lead.facts ?? {});
	assert.ok(readme && feature);
	assert.equal(readme.moved, 6);
	assert.match(
		String(readme.paths),
		/^PillApp\/WelcomeView\.swift \+18\/-0, README\.md \+20\/-5, PillApp\//u,
	);
	assert.equal(feature.moved, 0);
	assert.equal(feature.joinedClauses, true);
	assert.equal(
		feature.paths,
		"Orders/HistoryViewModel.swift +40/-2, project.yml +3/-0, Orders/InvoiceListResponse.swift +3/-3",
	);
	for (const lead of result.leads) {
		assert.equal("kinds" in (lead.facts ?? {}), false);
	}
	assert.doesNotMatch((result.directions ?? []).join(" "), /literally|concern|tangle|lapse/u);
});
