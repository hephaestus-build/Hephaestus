/**
 * Issue references as they appear in text: `#12`, `closes #12`, an issue number opening a branch
 * segment. These find syntax, not intent — a template, an example or an unrelated branch number
 * matches too, and a script reports them as candidates for the review to read in context.
 */

/**
 * `#N`. The trailing boundary rejects what looks like a reference but is not: a hex colour
 * (`#1a2b`), a unit (`#42px`), a version (`#1.2`). A sentence period after the number is still a
 * reference.
 */
const NUMBER_REF = /#(\d+)(?![\w]|\.[0-9])/g;

/** `closes #12`, `Fixes: #7`, `resolved #3`. */
const CLOSING_REF = /\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\b\s*:?\s*#(\d+)/gi;

/** An issue number opening a branch-slug segment: `18-foo`, `feat/18-foo`. */
const BRANCH_REF = /(?:^|\/)(\d{1,7})-/g;

function numbers(pattern: RegExp, text: string): number[] {
	const found = new Set<number>();
	for (const match of text.matchAll(pattern)) {
		const value = Number(match[1]);
		if (Number.isSafeInteger(value) && value > 0) found.add(value);
	}
	return [...found];
}

/** Every `#N` in the text, in order of first appearance. */
export function issueNumberReferences(text: string): number[] {
	return numbers(NUMBER_REF, text);
}

/** The `#N` a closing keyword precedes. */
export function closingReferences(text: string): number[] {
	return numbers(CLOSING_REF, text);
}

/** The issue numbers a branch name encodes. */
export function branchIssueReferences(branch: string): number[] {
	return numbers(BRANCH_REF, branch);
}
