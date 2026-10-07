import { describe, expect, it } from "vitest";

import { feedbackDisplayMarkdown } from "./feedback-display";
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

	it("leaves out the comment Hephaestus finds its copy by, even in a preview cut short", () => {
		expect(preview(`${MARKER}\nThe lookup collapses two failures`, true)).toBe(
			"The lookup collapses two failures…",
		);
	});

	it("shows only the authored words of a short posted body", () => {
		expect(preview(`${MARKER}\nKeep it focused.\n\n---\n${DISCLOSURE}\n${SETTINGS}\n`)).toBe(
			"Keep it focused.",
		);
	});

	it("leaves out a footer the cut ended inside", () => {
		expect(
			preview(`${MARKER}\nKeep it focused.\n\n---\n<sub>Practice review &middot; Mod`, true),
		).toBe("Keep it focused…");
	});

	it("keeps a footer quoted in code to the existing quote handling", () => {
		const body = `Quoted:\n\n\`\`\`md\n${DISCLOSURE}\n\`\`\`\n`;
		expect(feedbackDisplayMarkdown(body)).toBe(body);
		expect(preview(body)).toBe("Quoted:…");
	});
});

const MARKER = "<!-- hephaestus:practice-review:0b7d3c2e-8f14-4a6b-9c5d-2e1f0a3b4c5d -->";
const DISCLOSURE =
	"<sub>Practice review &middot; Model. This feedback is AI-generated and can be inaccurate. Answer or dispute it in [Hephaestus](https://example.com/respond).</sub>";
const SETTINGS = "<sub>[Why you see this and how to stop it](https://example.com/settings)</sub>";

describe("feedbackDisplayMarkdown", () => {
	it("drops the marker and unwraps the footer of a posted body, keeping its words and links", () => {
		expect(
			feedbackDisplayMarkdown(`${MARKER}\nKeep it focused.\n\n---\n${DISCLOSURE}\n${SETTINGS}\n`),
		).toBe(
			"Keep it focused.\n\n---\nPractice review &middot; Model. This feedback is AI-generated and can be inaccurate. Answer or dispute it in [Hephaestus](https://example.com/respond).\n[Why you see this and how to stop it](https://example.com/settings)\n",
		);
	});

	it("returns a body with neither as the same string", () => {
		const body = "Keep it focused.\r\n\r\n  - one   \n\n<sub>authored aside</sub>\n";
		expect(feedbackDisplayMarkdown(body)).toBe(body);
	});

	it.each([
		["a backtick fence", `\`\`\`md\n${MARKER}\n${DISCLOSURE}\n\`\`\`\n`],
		["a tilde fence", `~~~\n${MARKER}\n${DISCLOSURE}\n~~~\n`],
		["indented code", `Quoted:\n\n    ${MARKER}\n\n    ${DISCLOSURE}\n`],
		["inline code", `Write \`${MARKER}\` or \`${DISCLOSURE}\`.\n`],
		["other HTML", `<details>\n\n${DISCLOSURE} said by the author\n\n</details>\n`],
	])("leaves both alone when they are quoted in %s", (_case, body) => {
		expect(feedbackDisplayMarkdown(body)).toBe(body);
	});
});
