import { describe, expect, it } from "vitest";

import { feedbackPreviewText } from "./feedback-preview";

const preview = (bodyPreview: string | undefined, bodyTruncated = false) =>
	feedbackPreviewText({ bodyPreview, bodyTruncated });

describe("feedbackPreviewText", () => {
	it("returns a plain sentence unchanged", () => {
		expect(preview("You kept the controller focused.")).toBe("You kept the controller focused.");
	});

	it("reports no text when the feedback carries no body", () => {
		expect(preview(undefined)).toBeUndefined();
	});

	it("marks a body the server cut short", () => {
		expect(preview("The lookup collapses two failures", true)).toBe(
			"The lookup collapses two failures…",
		);
	});

	it("unwraps headings, emphasis and inline code", () => {
		expect(preview("## What worked\n\nThe **controller** stays `focused` on HTTP.")).toBe(
			"What worked The controller stays focused on HTTP.",
		);
	});

	it("keeps a link's words and drops its target", () => {
		expect(
			preview("See [the naming guideline](https://example.com/guide) for the longer version."),
		).toBe("See the naming guideline for the longer version.");
	});

	it("leaves out a fenced code block, and the line that introduced it", () => {
		expect(preview("You wrote:\n\n```java\nreturn null;\n```\n\nPrefer an Optional.")).toBe(
			"Prefer an Optional…",
		);
	});

	it("keeps a lead-in that introduces prose rather than a block", () => {
		expect(preview("Two options:\n\nRename it, or keep both for a release.")).toBe(
			"Two options: Rename it, or keep both for a release.",
		);
	});

	// The colon stays: it is what says the omitted thing was going to follow, which is the case here.
	it("keeps the lead-in when the fence it introduced was all the preview had left", () => {
		expect(preview("The lookup reads:\n\n```java\nreturn repository.findVisi", true)).toBe(
			"The lookup reads:…",
		);
	});

	it("drops the rule between two observations", () => {
		expect(preview("First point.\n\n---\n\nSecond point.")).toBe("First point. Second point…");
	});

	it("flattens a bulleted list into the line", () => {
		expect(preview("Two options:\n\n- rename the field\n- keep both for a release")).toBe(
			"Two options: rename the field keep both for a release",
		);
	});

	it("reports no text when the preview held nothing but a code fence", () => {
		expect(preview("```java\nreturn null;\n```")).toBeUndefined();
	});

	it("leaves out the marker and the HTML tags of a posted comment", () => {
		expect(
			preview(
				"<!-- hephaestus:practice-review:774b9e9b-2c40-4d6b-9e76-7094dda3a7a2 -->\nThe description needs its purpose and coverage.\n\n---\n<sub>Practice review &middot; gpt-6-luna.</sub>",
			),
		).toBe("The description needs its purpose and coverage. Practice review · gpt-6-luna…");
	});

	it("leaves out a marker the preview cut before it closed", () => {
		expect(preview("<!-- hephaestus:practice-review:774b9e9b", true)).toBeUndefined();
	});

	it("keeps a generic type in prose", () => {
		expect(preview("Return a List<String> here.")).toBe("Return a List<String> here.");
	});
});
