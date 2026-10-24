import { describe, expect, it } from "vitest";

import { ITEM_KIND } from "@/lib/item-kinds";

import { workSeenIn } from "./feedback-work-kind";

const ref = (
	id: number,
	kind: string = ITEM_KIND.pullRequest,
	provider: "GITHUB" | "GITLAB" | null = "GITHUB",
) => ({ id: String(id), kind, provider });

describe("workSeenIn", () => {
	it("names the provider's kind of work and counts each piece once", () => {
		expect(
			workSeenIn([ref(17), ref(20), ref(19), ref(17)])?.pieces.map((piece) => piece.id),
		).toStrictEqual(["17", "20", "19"]);
		expect(workSeenIn([ref(17), ref(20), ref(19), ref(17)])?.label).toBe(
			"Recurring in three pull requests",
		);
		expect(workSeenIn([ref(4, ITEM_KIND.pullRequest, "GITLAB")])?.label).toBe(
			"Recurring in one merge request",
		);
	});

	it("follows the kind the practice was tracked on", () => {
		expect(workSeenIn([ref(1, ITEM_KIND.issue), ref(2, ITEM_KIND.issue)])?.label).toBe(
			"Recurring in two issues",
		);
		expect(workSeenIn([ref(1, ITEM_KIND.thread, null)])?.label).toBe(
			"Recurring in one conversation",
		);
	});

	it("says pull or merge request when the providers differ", () => {
		expect(workSeenIn([ref(1), ref(2, ITEM_KIND.pullRequest, "GITLAB")])?.label).toBe(
			"Recurring in two pull or merge requests",
		);
	});

	it("falls back to a neutral noun for mixed or unknown kinds", () => {
		expect(workSeenIn([ref(1), ref(2, ITEM_KIND.issue)])?.label).toBe(
			"Recurring in two pieces of reviewed work",
		);
		expect(workSeenIn([ref(1, "scm.wiki_page")])?.label).toBe("Recurring in one piece of work");
	});

	it("goes to digits from ten", () => {
		expect(workSeenIn(Array.from({ length: 12 }, (_, index) => ref(index)))?.label).toBe(
			"Recurring in 12 pull requests",
		);
	});

	it("says nothing without work", () => {
		expect(workSeenIn([])).toBeUndefined();
	});
});
