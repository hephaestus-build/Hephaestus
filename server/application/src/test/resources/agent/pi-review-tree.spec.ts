import assert from "node:assert/strict";
import { describe, test } from "node:test";

import {
	buildReviewTree,
	mapConcurrent,
	missingPracticeSlugs,
	type ReviewPractice,
	resolveReviewConcurrency,
} from "../../../main/resources/agent/pi-review-tree.ts";

const practices: ReviewPractice[] = [
	{
		slug: "unsafe-input",
		readsSources: ["scm.repository.tree", "scm.pull-request.diff"],
	},
	{ slug: "description", readsSources: ["scm.pull-request.core"] },
	{
		slug: "review-replies",
		readsSources: ["scm.pull-request.core", "scm.review-threads"],
	},
	{
		slug: "acceptance-criteria",
		readsSources: ["scm.pull-request.core", "scm.linked-work-items"],
	},
];

void describe("buildReviewTree", () => {
	const laneCases: {
		name: string;
		practices: ReviewPractice[];
		expected: [string, string[]][];
	}[] = [
		{
			name: "routes pull request comments to the review lane",
			practices: [
				{
					slug: "specific-review-comments",
					readsSources: ["scm.pull-request.core", "scm.pull-request.comments"],
				},
			],
			expected: [["review-1", ["specific-review-comments"]]],
		},
		{
			name: "routes review threads to the review lane",
			practices: [
				{
					slug: "engages-with-review-feedback",
					readsSources: ["scm.pull-request.core", "scm.review-threads"],
				},
			],
			expected: [["review-1", ["engages-with-review-feedback"]]],
		},
		{
			name: "routes diffs and repository trees to the code lane",
			practices: [
				{
					slug: "tests-behavior-changes",
					readsSources: ["scm.pull-request.diff", "scm.repository.tree"],
				},
			],
			expected: [["code-1", ["tests-behavior-changes"]]],
		},
		{
			name: "routes linked work to the linked-work lane",
			practices: [
				{
					slug: "meets-linked-acceptance-criteria",
					readsSources: ["scm.pull-request.core", "scm.linked-work-items"],
				},
			],
			expected: [["linked-work-1", ["meets-linked-acceptance-criteria"]]],
		},
		{
			name: "routes pull request core data to the pull-request lane",
			practices: [
				{ slug: "clear-pull-request-description", readsSources: ["scm.pull-request.core"] },
			],
			expected: [["pull-request-1", ["clear-pull-request-description"]]],
		},
	];

	for (const scenario of laneCases) {
		void test(scenario.name, () => {
			const tree = buildReviewTree(scenario.practices, 4);

			assert.equal(tree.practiceCount, scenario.practices.length);
			assert.deepEqual(
				tree.groups.map((group) => [group.id, group.practiceSlugs]),
				scenario.expected,
			);
		});
	}

	void test("classifies mixed evidence lanes deterministically", () => {
		const mixed: ReviewPractice[] = laneCases.flatMap((scenario) => scenario.practices);
		const expected = [
			["pull-request-1", ["clear-pull-request-description"]],
			["linked-work-1", ["meets-linked-acceptance-criteria"]],
			["review-1", ["engages-with-review-feedback", "specific-review-comments"]],
			["code-1", ["tests-behavior-changes"]],
		];

		assert.deepEqual(
			buildReviewTree(mixed, 4).groups.map((group) => [group.id, group.practiceSlugs]),
			expected,
		);
		assert.deepEqual(
			buildReviewTree(mixed.toReversed(), 4).groups.map((group) => [group.id, group.practiceSlugs]),
			expected,
		);
	});

	void test("returns no work for an empty catalogue", () => {
		assert.deepEqual(buildReviewTree([], 4), { practiceCount: 0, groups: [] });
	});

	void test("uses the configured capacity instead of assuming a catalog size", () => {
		const dynamic = Array.from({ length: 7 }, (_, index) => ({
			slug: `practice-${index}`,
			readsSources: ["scm.pull-request.diff"],
		}));

		const tree = buildReviewTree(dynamic, 3);

		assert.equal(tree.practiceCount, 7);
		assert.deepEqual(
			tree.groups.map((group) => group.practiceSlugs.length),
			[3, 3, 1],
		);
		assert.deepEqual(
			tree.groups.map((group) => group.id),
			["code-1", "code-2", "code-3"],
		);
	});

	void test("keeps groups separate when area names normalize alike", () => {
		const tree = buildReviewTree(
			[
				{ slug: "api", area: "API / UX", readsSources: ["scm.pull-request.diff"] },
				{ slug: "ux", area: "API---UX", readsSources: ["scm.pull-request.diff"] },
			],
			2,
		);

		assert.deepEqual(
			tree.groups.map((group) => group.id),
			["code-1", "code-2"],
		);
	});

	void test("normalizes sources before classifying a group", () => {
		const tree = buildReviewTree(
			[
				{ slug: "b", readsSources: [" scm.pull-request.core "] },
				{
					slug: "a",
					readsSources: ["scm.pull-request.core", "scm.pull-request.core"],
				},
			],
			2,
		);

		assert.deepEqual(tree.groups[0], {
			id: "pull-request-1",
			lane: "pull-request",
			practiceSlugs: ["a", "b"],
		});
	});

	void test("rejects invalid capacity, blank slugs, and duplicate slugs", () => {
		assert.throws(() => buildReviewTree(practices, 0), /positive integer/u);
		assert.throws(() => buildReviewTree([{ slug: " " }], 1), /non-empty slug/u);
		assert.throws(
			() => buildReviewTree([{ slug: "same" }, { slug: "same" }], 1),
			/duplicate practice slug: same/u,
		);
	});
});

void test("missingPracticeSlugs requires explicit coverage for every selected practice", () => {
	assert.deepEqual(missingPracticeSlugs(["a", "b", "c"], ["c", "a"]), ["b"]);
	assert.deepEqual(missingPracticeSlugs(["a", "b"], ["b", "a"]), []);
});

void test("derives bounded review concurrency", () => {
	assert.equal(resolveReviewConcurrency(undefined, 1), 1);
	assert.equal(resolveReviewConcurrency(undefined, 16), 4);
	assert.equal(resolveReviewConcurrency(undefined, 27), 7);
	assert.equal(resolveReviewConcurrency(undefined, 100), 8);
	assert.equal(resolveReviewConcurrency("1", 27), 1);
	assert.equal(resolveReviewConcurrency("8", 1), 8);
	for (const invalid of ["0", "9", "2.5", "nope"]) {
		assert.throws(() => resolveReviewConcurrency(invalid, 27), /integer from 1 to 8/u);
	}
	assert.throws(() => resolveReviewConcurrency(undefined, -1), /non-negative integer/u);
});

void test("mapConcurrent bounds active work and preserves input order", async () => {
	const started: number[] = [];
	const releases = Array.from({ length: 4 }, () => Promise.withResolvers<undefined>());
	const firstPairStarted = Promise.withResolvers<undefined>();
	const thirdStarted = Promise.withResolvers<undefined>();
	const resultPromise = mapConcurrent([0, 1, 2, 3], 2, async (_, index) => {
		started.push(index);
		if (started.length === 2) {
			firstPairStarted.resolve(undefined);
		}
		if (started.length === 3) {
			thirdStarted.resolve(undefined);
		}
		await releases[index]?.promise;
		return index;
	});

	await firstPairStarted.promise;
	assert.deepEqual(started, [0, 1]);
	releases[1]?.resolve(undefined);
	await thirdStarted.promise;
	assert.deepEqual(started, [0, 1, 2]);
	for (const release of releases) {
		release.resolve(undefined);
	}
	const results = await resultPromise;
	assert.deepEqual(results, [0, 1, 2, 3]);
});
