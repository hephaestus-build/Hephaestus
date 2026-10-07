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
		String.raw`(?: &middot; [^<>\n]+?)?\. This feedback is AI-generated and can be inaccurate\. Answer or dispute it in \[Hephaestus\]${LINK}\.`,
	],
	["AI-generated feedback", String.raw`\. Answer or dispute it in \[Hephaestus\]${LINK}\.`],
	["[Why you see this and how to stop it]", LINK],
].map(([start = "", rest = ""]) => ({
	start: `<sub>${start}`,
	pattern: new RegExp(
		`^<sub>(?<text>${start.replaceAll(/[[\]().]/gu, String.raw`\$&`)}${rest})</sub>$`,
		"u",
	),
}));

/** The formatter's rule between the feedback and its footer. */
const FOOTER_RULE = "---";

/**
 * A stored comment as it reads on the work, for a renderer that prints HTML as text.
 *
 * The provider hides the comment Hephaestus finds its copy by and renders the footer's `<sub>` tags;
 * this renderer would print both. So a block that is exactly that comment is left out, and the footer
 * lines Hephaestus wrote at the end lose their tags but keep their words and links. Nothing else
 * changes: authored text, other HTML and code, however it quotes either, stay as written, and a body
 * with neither comes back as the same string.
 */
export function feedbackDisplayMarkdown(body: string): string {
	return rewrite(body, false, false);
}

/**
 * The authored words of a stored comment, for an excerpt: the comment and the whole footer go,
 * along with the rule the formatter puts before it.
 *
 * A `cut` excerpt can stop inside the footer. Its last line then goes as well, but only when it
 * already starts with the full opening words of a footer line, such as `<sub>Practice review`. A
 * shorter remnant cannot be told from the author's own text and stays.
 */
export function feedbackExcerptMarkdown(body: string, cut: boolean): string {
	return rewrite(body, true, cut);
}

function rewrite(body: string, excerpt: boolean, cut: boolean): string {
	const blocks = parseMarkdownIntoBlocks(body);
	// The blocks are the body's own text, cut apart; anything the split normalized is left alone.
	if (blocks.join("") !== body) {
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
	let footerAt: number | undefined;
	for (let index = shown.length - 1; index >= 0; index -= 1) {
		const block = shown[index] ?? "";
		if (!hasText(block.trim())) {
			continue;
		}
		const unwrapped = unwrapFooter(block, cut && footerAt === undefined);
		if (unwrapped === undefined) {
			if (excerpt && footerAt !== undefined && block.trimEnd() === FOOTER_RULE) {
				shown[index] = "";
			}
			break;
		}
		shown[index] = excerpt ? "" : unwrapped;
		footerAt = index;
		changed = true;
	}
	return changed ? shown.join("") : body;
}

/**
 * The block with each footer line's words in place of its `<sub>` line, if every line is one. With
 * `cut`, the block's last line may instead be a footer line the cut ended inside; it is dropped.
 */
function unwrapFooter(block: string, cut: boolean): string | undefined {
	const lines = block.split("\n");
	let last = lines.length - 1;
	while (last >= 0 && !hasText(lines[last]?.trim())) {
		last -= 1;
	}
	const unwrapped: string[] = [];
	for (const [index, line] of lines.entries()) {
		if (!hasText(line.trim())) {
			unwrapped.push(line);
			continue;
		}
		const text = FOOTER_LINES.map(({ pattern }) => pattern.exec(line)?.groups?.text).find(hasText);
		if (text !== undefined) {
			unwrapped.push(text);
		} else if (cut && index === last && FOOTER_LINES.some(({ start }) => line.startsWith(start))) {
			unwrapped.push("");
		} else {
			return undefined;
		}
	}
	return unwrapped.join("\n");
}
