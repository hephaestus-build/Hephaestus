import { describe, expect, it } from "vitest";

import {
	type AppliesWhenDraft,
	appliesWhenDraftOf,
	parseAppliesWhen,
	pathPatternProblem,
} from "./applies-when";
import { mockIssueItemType, mockPullRequestItemType } from "./fixtures";

const swift = {
	anyOf: [{ changedPath: ["**/*.swift"] }],
	otherwise: "the change has no Swift code",
};

function draft(
	clauses: AppliesWhenDraft["clauses"],
	otherwise = swift.otherwise,
): AppliesWhenDraft {
	return { clauses, otherwise };
}

describe("parseAppliesWhen", () => {
	it("keeps complete conditions and the sentence for when they do not hold", () => {
		expect(parseAppliesWhen(draft(swift.anyOf), mockPullRequestItemType)).toStrictEqual({
			value: swift,
		});
	});

	it("clears conditions independently of a retained Otherwise draft", () => {
		expect(parseAppliesWhen(draft([], "left over"), mockPullRequestItemType)).toStrictEqual({
			value: null,
		});
	});

	it.each([{ changedPath: [" "] }, { diffContains: [] }])(
		"requires a value for each condition: %j",
		(clause) => {
			expect(parseAppliesWhen(draft([clause]), undefined).clausesError).toBe(
				"Enter a value for each condition.",
			);
		},
	);

	it("keeps a merged condition, which takes no value", () => {
		expect(
			parseAppliesWhen(draft([{ merged: true }], "it has not merged"), mockPullRequestItemType),
		).toStrictEqual({ value: { anyOf: [{ merged: true }], otherwise: "it has not merged" } });
	});

	it("requires choosing a source for a source condition", () => {
		expect(parseAppliesWhen(draft([{ hasItems: "" }]), mockPullRequestItemType).clausesError).toBe(
			"Choose a source.",
		);
	});

	it("refuses a changed path that leaves the repository", () => {
		expect(parseAppliesWhen(draft([{ changedPath: ["../secrets"] }]), undefined).clausesError).toBe(
			"A pattern cannot leave the repository: remove “..” from “../secrets”.",
		);
	});

	it("requires the Otherwise sentence once there are conditions", () => {
		expect(parseAppliesWhen(draft(swift.anyOf, " "), mockPullRequestItemType).otherwiseError).toBe(
			"Say why the practice does not apply when no condition holds.",
		);
	});

	it("permits a condition only when the item type can check it", () => {
		expect(parseAppliesWhen(draft(swift.anyOf), mockIssueItemType).clausesError).toBe(
			"This kind of work cannot check one of these conditions.",
		);
	});

	it("preserves indentation in a saved diff phrase", () => {
		const saved = {
			anyOf: [{ diffContains: ["    return value;"] }],
			otherwise: "The diff has no matching line.",
		};
		expect(
			parseAppliesWhen(appliesWhenDraftOf(saved), mockPullRequestItemType).value,
		).toStrictEqual(saved);
	});

	it("restores saved conditions without a JSON representation", () => {
		expect(parseAppliesWhen(appliesWhenDraftOf(swift), undefined).value).toStrictEqual(swift);
		expect(appliesWhenDraftOf(null)).toStrictEqual({ clauses: [], otherwise: "" });
	});
});

describe("pathPatternProblem", () => {
	it.each(["src/**/*.ts", "**/*", "packages/app/src/main.ts"])("accepts %s", (pattern) => {
		expect(pathPatternProblem(pattern)).toBeUndefined();
	});

	it.each([
		"/etc/passwd",
		"C:/repo",
		String.raw`src\main.ts`,
		"../outside",
		"src/../../outside",
		".git/config",
		"src/.git/hooks",
		"src//main.ts",
		"./src",
		"src/{a,b}.ts",
		"src/\0.ts",
		"a".repeat(513),
	])("refuses %s", (pattern) => {
		expect(pathPatternProblem(pattern)).toBeDefined();
	});
});
