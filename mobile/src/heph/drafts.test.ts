import { describe, expect, it } from "vitest";

import { feedbackDraft, openingDraft, readDraft, writeDraft } from "./drafts";

const TEAM = { epoch: 1, workspaceSlug: "team" };

describe("drafts", () => {
	it("keeps a draft for its conversation within its session and workspace", () => {
		writeDraft(TEAM, "new", "half a question");

		expect(readDraft(TEAM, "new")).toBe("half a question");
		expect(readDraft({ epoch: 2, workspaceSlug: "team" }, "new")).toBe("");
		expect(readDraft({ epoch: 1, workspaceSlug: "other" }, "new")).toBe("");
	});

	it("forgets the drafts of a scope once another scope writes", () => {
		writeDraft(TEAM, "thread-1", "draft");
		writeDraft({ epoch: 1, workspaceSlug: "other" }, "thread-2", "elsewhere");

		expect(readDraft(TEAM, "thread-1")).toBe("");
	});

	it("names the feedback without claiming anything more", () => {
		expect(
			feedbackDraft({ headline: "Name the doubt", practiceName: "Actionable review comments" }),
		).toContain("“Actionable review comments”: “Name the doubt”");
	});
});

describe("openingDraft", () => {
	it("prefers what the person already typed", () => {
		expect(openingDraft("my words", { headline: "h", practiceName: "p" }, "0")).toBe("my words");
	});

	it("names the feedback it was opened from, else the chosen starter", () => {
		expect(openingDraft("", { headline: "h", practiceName: "p" }, undefined)).toContain("“p”: “h”");
		expect(openingDraft("", undefined, "1")).toBe(
			"What makes a review comment easy to respond to?",
		);
		expect(openingDraft("", undefined, "99")).toBe("");
	});
});
