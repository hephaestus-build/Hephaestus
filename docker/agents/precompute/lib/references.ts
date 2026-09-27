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
 * (`#1a2b`), a unit (`#42px`), a version (`#1.2`). A sentence period after the number is still a
 * reference.
 */
const NUMBER_REF = /#(?<number>\d+)(?![\w]|\.[0-9])/gu;

/** `closes #12`, `Fixes: #7`, `resolved #3`. */
const CLOSING_REF = new RegExp(
	String.raw`\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\b\s*:?\s*${NUMBER_REF.source}`,
	"giu",
);

/** `Related to #12`: GitLab's wording for an issue a change belongs to without closing it. */
const RELATED_REF = new RegExp(String.raw`\brelated\s+to\s*:?\s*${NUMBER_REF.source}`, "giu");

/** An issue number opening a branch-slug segment: `18-foo`, `feat/18-foo`. */
const BRANCH_REF = /(?:^|\/)(?<number>\d{1,7})-/gu;

/**
 * An HTML comment is not rendered, and neither provider links an issue from inside one: a template's
 * `<!-- Example: #12 -->` is the template's text, not the author's reference. The server's linked-work-item
 * lookup applies the same rule.
 */
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

/** Raw text, where a comment is still in the string; parsed prose already has it as its own node. */
function numbers(pattern: RegExp, text: string): number[] {
	return matched(pattern, text.replace(HTML_COMMENT, ""));
}

/** Every `#N` in the text, in order of first appearance. */
export function issueNumberReferences(text: string): number[] {
	return numbers(NUMBER_REF, text);
}

/**
 * Code, quotations and images show something rather than state the author's own words, and struck-out
 * text is words the author withdrew.
 */
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

/**
 * The runs of the author's own text in one paragraph or heading. Code or HTML inside it ends a run
 * rather than vanishing, so the words on either side never join into a phrase nobody wrote.
 */
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

/** The author's own prose, each paragraph or heading apart, so no phrase runs from one into the next. */
function blocks(node: MarkdownNode): string[] {
	if (SHOWN.has(node.type)) {
		return [];
	}
	if (node.type === "paragraph" || node.type === "heading") {
		return runs(node);
	}
	return (node.children ?? []).flatMap(blocks);
}

/** The numbers a phrase names in the author's own prose, one paragraph or heading at a time. */
function stated(pattern: RegExp, text: string): number[] {
	const tree = fromMarkdown(text, {
		extensions: [gfmStrikethrough()],
		mdastExtensions: [gfmStrikethroughFromMarkdown()],
	});
	return [...new Set(blocks(tree).flatMap((block) => matched(pattern, block)))];
}

/** The `#N` a closing keyword precedes in the author's own prose. */
export function closingReferences(text: string): number[] {
	return stated(CLOSING_REF, text);
}

/** The `#N` a `Related to` precedes in the author's own prose. */
export function relatedReferences(text: string): number[] {
	return stated(RELATED_REF, text);
}

/** The issue numbers a branch name encodes. */
export function branchIssueReferences(branch: string): number[] {
	return numbers(BRANCH_REF, branch);
}
