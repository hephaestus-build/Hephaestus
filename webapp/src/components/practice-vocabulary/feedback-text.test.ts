import { describe, expect, it } from "vitest";

import type { ReviewedWorkRef } from "@/api/types.gen";

import { count, countedWork, linkWork, list, text } from "./feedback-text";

const pullRequest = (n: number): ReviewedWorkRef => ({
	id: String(n),
	kind: "scm.pull_request",
	label: `#${n}`,
	url: `https://github.example/pr/${n}`,
});

describe("linkWork", () => {
	it("links the work the card knows and leaves a number it does not as words", () => {
		const known = [pullRequest(6), pullRequest(7), pullRequest(8)];
		expect(linkWork("#6 and #7 list the files touched; #99 is elsewhere.", known)).toStrictEqual([
			{ type: "work", ref: pullRequest(6) },
			{ type: "text", text: " and " },
			{ type: "work", ref: pullRequest(7) },
			{ type: "text", text: " list the files touched; #99 is elsewhere." },
		]);
	});

	it("links a merge request named with either sigil, in the provider's own label", () => {
		const mergeRequest = { ...pullRequest(21), label: "!21" };
		expect(linkWork("In #21 and !21 the fix rode along.", [mergeRequest])).toStrictEqual([
			{ type: "text", text: "In " },
			{ type: "work", ref: mergeRequest },
			{ type: "text", text: " and " },
			{ type: "work", ref: mergeRequest },
			{ type: "text", text: " the fix rode along." },
		]);
	});

	it("keeps an issue and a merge request that share a number apart by their sigil", () => {
		const issue = { ...pullRequest(21), id: "issue-21", kind: "scm.issue", label: "#21" };
		const mergeRequest = { ...pullRequest(21), id: "mr-21", label: "!21" };
		expect(linkWork("#21 says what !21 does.", [issue, mergeRequest])).toStrictEqual([
			{ type: "work", ref: issue },
			{ type: "text", text: " says what " },
			{ type: "work", ref: mergeRequest },
			{ type: "text", text: " does." },
		]);
	});

	it("leaves a number two known pieces carry as words when no label matches it", () => {
		const known = [
			{ ...pullRequest(21), id: "server-21", label: "!21" },
			{ ...pullRequest(21), id: "webapp-21", label: "!21" },
		];
		expect(linkWork("In #21 the fix rode along.", known)).toStrictEqual([
			{ type: "text", text: "In #21 the fix rode along." },
		]);
	});

	it("keeps a run with no reference as one piece of text", () => {
		expect(linkWork("Just a note.", [pullRequest(6)])).toStrictEqual([
			{ type: "text", text: "Just a note." },
		]);
	});
});

describe("list", () => {
	it.each([
		["one item stands alone", ["A"], ["A"]],
		["two items are joined by and", ["A", "B"], ["A", " and ", "B"]],
		["three items take commas and a final and", ["A", "B", "C"], ["A", ", ", "B", " and ", "C"]],
	])("%s", (_name, items: string[], expected: string[]) => {
		expect(list(items.map((item) => [text(item)]))).toStrictEqual(
			expected.map((segment) => text(segment)),
		);
	});
});

describe("count", () => {
	it.each([
		[1, undefined, "one practice"],
		[9, undefined, "nine practices"],
		[10, undefined, "10 practices"],
		[3, true, "3 practices"],
	])("writes %i as the prose does", (n: number, digits: boolean | undefined, expected: string) => {
		expect(count(n, "practice", "practices", digits)).toBe(expected);
	});
});

describe("countedWork", () => {
	it.each([
		["GITHUB", 4, "four pull requests"],
		["GITLAB", 4, "four merge requests"],
		["GITLAB", 1, "one merge request"],
	] as const)("names a pull request at %s by that provider's noun", (provider, n, expected) => {
		expect(countedWork("scm.pull_request", n, provider)).toBe(expected);
	});
});
