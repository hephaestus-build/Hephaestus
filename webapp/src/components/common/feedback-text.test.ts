import { describe, expect, it } from "vitest";

import type { ReviewedWorkRef } from "@/api/types.gen";

import { linkWork } from "./feedback-text";

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

	it("keeps a run with no reference as one piece of text", () => {
		expect(linkWork("Just a note.", [pullRequest(6)])).toStrictEqual([
			{ type: "text", text: "Just a note." },
		]);
	});
});
