import { parseMarkdownIntoBlocks } from "streamdown";

import { hasText } from "@/lib/text";

/** The comment that lets Hephaestus find its own copy of a piece of feedback on the work again. */
const TRANSPORT_MARKER =
	/^<!-- hephaestus:(?:practice-review|approved-feedback):[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12} -->$/u;

const LINK = String.raw`\(https?:\/\/[^\s()<>]+\)`;

/**
 * The footer lines Hephaestus appends to every comment it posts, each wrapped in `<sub>` for the
 * provider: the words a line starts with, then the pattern for the rest of it.
 */
const FOOTER_LINES = [
	[
		"Practice review",
		String.raw`(?: &middot; [^<>\n]+?)?\. This feedback is AI-generated and can be inaccurate\. Answer or disagree in \[Hephaestus\]${LINK}\.`,
	],
	["AI-generated feedback", String.raw`\. Answer or disagree in \[Hephaestus\]${LINK}\.`],
	["[Why you see this and how to stop it]", LINK],
].map(
	([start = "", rest = ""]) =>
		new RegExp(
			`^<sub>(?<text>${start.replaceAll(/[[\]().]/gu, String.raw`\$&`)}${rest})</sub>$`,
			"u",
		),
);

/** The hidden line where the server's footer starts; the provider shows nothing for it. */
const FOOTER_START = /^<!-- hephaestus-disclosure:\d+ -->$/u;

/**
 * A stored comment as it reads on the work, for a renderer that prints HTML as text.
 *
 * The provider hides the comment Hephaestus finds its copy by and renders the footer's `<sub>` tags;
 * this renderer would print both. So a block that is exactly that comment is left out, and the footer
 * lines Hephaestus wrote at the end lose their tags but keep their words and links. Nothing else
 * changes apart from Markdown line endings when a marker or footer is rewritten. Authored text,
 * other HTML and code stay intact, and a body with neither comes back as the same string.
 */
export function feedbackDisplayMarkdown(body: string): string {
	const blocks = parseMarkdownIntoBlocks(body);
	// CommonMark normalizes line endings; other parser rewrites must not change authored text.
	const normalized = body.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
	if (blocks.join("") !== normalized) {
		return body;
	}
	let changed = false;
	const shown = blocks.map((block) => {
		if (TRANSPORT_MARKER.test(block.trimEnd())) {
			changed = true;
			return "";
		}
		return block;
	});
	for (let index = shown.length - 1; index >= 0; index -= 1) {
		const block = shown[index] ?? "";
		if (!hasText(block.trim())) {
			continue;
		}
		const unwrapped = unwrapFooter(block);
		if (unwrapped === undefined) {
			break;
		}
		shown[index] = unwrapped;
		changed = true;
	}
	return changed ? shown.join("") : body;
}

/** The block with each footer line's words in place of its `<sub>` line, if every line is one. */
function unwrapFooter(block: string): string | undefined {
	const unwrapped: string[] = [];
	for (const line of block.split("\n")) {
		if (!hasText(line.trim())) {
			unwrapped.push(line);
			continue;
		}
		if (FOOTER_START.test(line.trim())) {
			continue;
		}
		const text = FOOTER_LINES.map((pattern) => pattern.exec(line)?.groups?.text).find(hasText);
		if (text === undefined) {
			return undefined;
		}
		unwrapped.push(text);
	}
	return unwrapped.join("\n");
}
