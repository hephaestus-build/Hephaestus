/**
 * Issue references as they appear in text: `#12`, `closes #12`, an issue number opening a branch
 * segment. These find syntax, not intent — a template, an example or an unrelated branch number
 * matches too, and a script reports them as candidates for the review to read in context.
 */

import { fromMarkdown } from "mdast-util-from-markdown";
import { gfmStrikethroughFromMarkdown } from "mdast-util-gfm-strikethrough";
import { gfmStrikethrough } from "micromark-extension-gfm-strikethrough";

/**
 * `#N`. The trailing boundary rejects what looks like a reference but is not: a hex colour
 * (`#1a2b`), a piece (`#42px`), a version (`#1.2`). A sentence period after the number is still a
 * reference.
 */
const NUMBER_REF = /#(?<number>\d+)(?![\w]|\.[0-9])/gu;

const CLOSING_REF = new RegExp(
	String.raw`\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\b\s*:?\s*${NUMBER_REF.source}`,
	"giu",
);

const RELATED_REF = new RegExp(String.raw`\brelated\s+to\s*:?\s*${NUMBER_REF.source}`, "giu");

const BRANCH_REF = /(?:^|\/)(?<number>\d{1,7})-/gu;

// Providers do not link issue references inside hidden HTML comments.
const HTML_COMMENT = /<!--[\s\S]*?-->/gu;

function matched(pattern: RegExp, text: string): number[] {
	const found = new Set<number>();
	for (const match of text.matchAll(pattern)) {
		const value = Number(match.groups?.number);
		if (Number.isSafeInteger(value) && value > 0) {
			found.add(value);
		}
	}
	return [...found];
}

// Raw Markdown comments remain in text; parsed prose separates them into nodes.
function numbers(pattern: RegExp, text: string): number[] {
	return matched(pattern, withoutHtmlComments(text));
}

// Retain a boundary so removed comments cannot join separate tokens.
export function withoutHtmlComments(text: string): string {
	let result = text;
	let previous;
	do {
		previous = result;
		result = result.replaceAll(HTML_COMMENT, " ");
	} while (result !== previous);
	return result;
}

export function issueNumberReferences(text: string): number[] {
	return numbers(NUMBER_REF, text);
}

// Exclude quotations, code, images, and withdrawn text from the author's own prose.
const SHOWN = new Set([
	"code",
	"inlineCode",
	"blockquote",
	"html",
	"image",
	"imageReference",
	"delete",
]);

interface MarkdownNode {
	type: string;
	value?: string;
	children?: MarkdownNode[];
}

// Code and HTML must separate prose so adjacent words cannot form an invented phrase.
function runs(node: MarkdownNode): string[] {
	const found = [""];
	const walk = (parent: MarkdownNode) => {
		for (const child of parent.children ?? []) {
			if (SHOWN.has(child.type)) {
				found.push("");
			} else if (child.type === "text") {
				found[found.length - 1] += child.value ?? "";
			} else {
				walk(child);
			}
		}
	};
	walk(node);
	return found;
}

// Paragraph and heading boundaries must not create cross-boundary phrases.
function blocks(node: MarkdownNode): string[] {
	if (SHOWN.has(node.type)) {
		return [];
	}
	if (node.type === "paragraph" || node.type === "heading") {
		return runs(node);
	}
	return (node.children ?? []).flatMap(blocks);
}

function stated(pattern: RegExp, text: string): number[] {
	const tree = fromMarkdown(text, {
		extensions: [gfmStrikethrough()],
		mdastExtensions: [gfmStrikethroughFromMarkdown()],
	});
	return [...new Set(blocks(tree).flatMap((block) => matched(pattern, block)))];
}

export function closingReferences(text: string): number[] {
	return stated(CLOSING_REF, text);
}

export function relatedReferences(text: string): number[] {
	return stated(RELATED_REF, text);
}

/** URLs are reference candidates; their text does not establish a provider closing relationship. */
export function issueUrlReferences(text: string): number[] {
	return numbers(/https?:\/\/[^\s)]+?\/(?:-\/)?issues\/(?<number>\d+)/giu, text);
}

export function branchIssueReferences(branch: string): number[] {
	return numbers(BRANCH_REF, branch);
}

/** The issue numbers that a text names: what it closes, relates to or only mentions, and its issue links. */
export function issueReferences(text: string) {
	return {
		closing: closingReferences(text),
		related: relatedReferences(text),
		mentioned: issueNumberReferences(text),
		urls: issueUrlReferences(text),
	};
}
