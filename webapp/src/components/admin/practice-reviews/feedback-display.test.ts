import { describe, expect, it } from "vitest";

import { feedbackDisplayMarkdown } from "./feedback-display";

const MARKER = "<!-- hephaestus:practice-review:0b7d3c2e-8f14-4a6b-9c5d-2e1f0a3b4c5d -->";
const DISCLOSURE =
	"<sub>Practice review &middot; Model. This feedback is AI-generated and can be inaccurate. Answer or disagree in [Hephaestus](https://example.com/respond).</sub>";
const SETTINGS = "<sub>[Why you see this and how to stop it](https://example.com/settings)</sub>";

describe("feedbackDisplayMarkdown", () => {
	it.each(["\r\n", "\r"])("removes the transport marker with %j line endings", (ending) => {
		const body = `${MARKER}${ending}Keep it focused.${ending}`;
		expect(feedbackDisplayMarkdown(body)).toBe("Keep it focused.\n");
	});

	it("drops the marker and unwraps the footer of a posted body, keeping its words and links", () => {
		expect(
			feedbackDisplayMarkdown(`${MARKER}\nKeep it focused.\n\n---\n${DISCLOSURE}\n${SETTINGS}\n`),
		).toBe(
			"Keep it focused.\n\n---\nPractice review &middot; Model. This feedback is AI-generated and can be inaccurate. Answer or disagree in [Hephaestus](https://example.com/respond).\n[Why you see this and how to stop it](https://example.com/settings)\n",
		);
	});

	it("drops the hidden line where the footer starts", () => {
		expect(
			feedbackDisplayMarkdown(
				`${MARKER}\nKeep it focused.\n\n<!-- hephaestus-disclosure:1 -->\n${DISCLOSURE}\n${SETTINGS}\n`,
			),
		).toBe(
			"Keep it focused.\n\nPractice review &middot; Model. This feedback is AI-generated and can be inaccurate. Answer or disagree in [Hephaestus](https://example.com/respond).\n[Why you see this and how to stop it](https://example.com/settings)\n",
		);
	});

	it("returns a body with neither as the same string", () => {
		const body = "Keep it focused.\r\n\r\n  - one   \n\n<sub>authored aside</sub>\n";
		expect(feedbackDisplayMarkdown(body)).toBe(body);
	});

	it("preserves unmodified text and a quoted marker with CRLF line endings", () => {
		const plain = "Keep it focused.\r\n\r\n  - one   \r\n";
		const quoted = `\`\`\`md\r\n${MARKER}\r\n\`\`\`\r\n`;
		expect(feedbackDisplayMarkdown(plain)).toBe(plain);
		expect(feedbackDisplayMarkdown(quoted)).toBe(quoted);
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
