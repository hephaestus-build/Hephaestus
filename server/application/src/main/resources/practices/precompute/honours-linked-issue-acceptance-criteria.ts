// Surface linked-issue references and acceptance-criteria blocks, not compliance verdicts.
import { readContextJson } from "../lib/context.ts";
import { isJsonObject } from "../lib/practice-contract.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const CLOSE_KEYWORD = /\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\b/giu;
// "closes #12", "fixes GH-12", "resolves group/proj#12" — capture the trailing issue number.
const CLOSE_REF =
	/\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s*:?\s+(?:[\w./~-]*[#!]|GH-)(?<number>\d+)/giu;
// Full issue/MR URLs on either host: .../issues/12, .../-/issues/12.
const ISSUE_URL = /https?:\/\/[^\s)]+?\/(?:-\/)?issues\/(?<number>\d+)/giu;
// Issue number embedded in a branch name: feature/123-foo, issue-123, 123-fix-thing, bugfix/GH-123.
const BRANCH_REF = /(?:^|[/_-])(?:issue[-_]?|gh[-_]?|#)?(?<number>\d{1,6})(?:[-_/]|$)/giu;

// Every pattern below is global and captures the issue number as `number`; matchAll iterates a clone,
// so the shared module-level regexes keep a lastIndex of 0 between calls.
function collect(re: RegExp, text: string, into: Set<string>): void {
	if (!text) {
		return;
	}
	re.lastIndex = 0;
	for (const match of text.matchAll(re)) {
		into.add(`#${match.groups?.number}`);
	}
}

interface LinkedWorkItem {
	number?: number | string;
	iid?: number | string;
	title?: string;
	// The SCM connector (LinkedWorkItemContentSource) emits the issue body under `bodyExcerpt`; keep
	// `body`/`description` as host-general fallbacks for other connectors.
	bodyExcerpt?: string;
	body?: string;
	description?: string;
}

function optionalText(value: unknown): string | undefined {
	return typeof value === "string" ? value : undefined;
}

function optionalRef(value: unknown): number | string | undefined {
	return typeof value === "number" || typeof value === "string" ? value : undefined;
}

// An entry carrying none of the fields above still counts as a linked item: `linkedIssueCount` reports
// how many items the connector linked, which is a fact about the tracker, not about how much of each
// entry this side could read.
function toLinkedWorkItem(entry: unknown): LinkedWorkItem {
	if (!isJsonObject(entry)) {
		return {};
	}
	return {
		number: optionalRef(entry.number),
		iid: optionalRef(entry.iid),
		title: optionalText(entry.title),
		bodyExcerpt: optionalText(entry.bodyExcerpt),
		body: optionalText(entry.body),
		description: optionalText(entry.description),
	};
}

// Unwrap whatever shape linked_work_items.json carries into a flat item list (or null if not a usable object).
function unwrapLinkedItems(data: unknown): LinkedWorkItem[] | null {
	if (Array.isArray(data)) {
		return data.map(toLinkedWorkItem);
	}
	if (!isJsonObject(data)) {
		return null;
	}
	// The SCM connector wraps items under `workItems`; check it FIRST, then host-general fallbacks.
	if (Array.isArray(data.workItems)) {
		return data.workItems.map(toLinkedWorkItem);
	}
	if (Array.isArray(data.items)) {
		return data.items.map(toLinkedWorkItem);
	}
	return [toLinkedWorkItem(data)];
}

// A checkable acceptance-criteria artifact: an AC/DoD heading or a "- [ ]" checklist in the issue body.
function acFacts(body: string): { heading: boolean; boxes: number } {
	const heading =
		/(?:acceptance criteria|definition of done|\bDoD\b|done when|expected (?:outcome|result|behaviou?r))/iu.test(
			body,
		);
	const boxes = (body.match(/^[\s>]*[-*]\s+\[[ xX]\]/gmu) ?? []).length;
	return { heading, boxes };
}

function itemBody(item: LinkedWorkItem): string {
	return (item.bodyExcerpt ?? item.body ?? item.description ?? "").trim();
}

interface LinkedIssueFacts {
	bodyPresent: boolean;
	acHeading: boolean;
	acBoxes: number;
}

function linkedIssueFacts(linked: LinkedWorkItem[] | null): LinkedIssueFacts {
	const facts: LinkedIssueFacts = { bodyPresent: false, acHeading: false, acBoxes: 0 };
	for (const item of linked ?? []) {
		const body = itemBody(item);
		facts.bodyPresent ||= body.length > 0;
		const f = acFacts(body);
		facts.acHeading ||= f.heading;
		facts.acBoxes += f.boxes;
	}
	return facts;
}

function referenceDirection(
	closingRefs: string[],
	branchRefs: string[],
	keywordHits: number,
): string {
	if (closingRefs.length > 0) {
		return `Issue-reference syntax candidate(s) (${closingRefs.join(", ")}) from title/body — inspect the exact mention in context, including templates/examples, before establishing that the author claims to close this issue. Only then map those criteria to the change.`;
	}
	if (branchRefs.length > 0) {
		return `Branch name encodes a possible issue number (${branchRefs.join(", ")}) but no closing keyword/URL was found in title/body — treat this as a traceability candidate, not a closing claim: confirm it maps to a tracked issue before mapping any acceptance criteria.`;
	}
	if (keywordHits > 0) {
		return `Closing keyword(s) present but no issue number parsed — investigate whether a tracked issue is linked another way before treating this as having no acceptance criteria to honour.`;
	}
	return `No closing reference, issue URL, or branch-encoded number found in title/body/branch — confirm there is genuinely no linked tracker issue before concluding there are no acceptance criteria to honour.`;
}

function linkedContextDirection(
	linked: LinkedWorkItem[] | null,
	{ bodyPresent, acHeading, acBoxes }: LinkedIssueFacts,
): string {
	if (linked === null) {
		return `No linked_work_items.json was projected into context — the linked issue's body and its acceptance-criteria block are NOT visible here, so this practice cannot be assessed from the diff alone.`;
	}
	if (bodyPresent) {
		return `Candidate issue facts: bodyPresent=true, acceptanceCriteriaBlockPresent=${acHeading || acBoxes > 0} (heading=${acHeading}, checkboxes=${acBoxes}) — these are text facts, not applicability. Confirm an authored closing claim in the original mention context before mapping criteria to done or deferred.`;
	}
	return `Linked-issue context exists but carries no issue body — there is no quotable acceptance-criteria text to map the change against.`;
}

export default async function honoursLinkedIssueAcceptanceCriteria(
	_repoPath: string,
	_diff: Map<string, DiffFile>,
	meta: PullRequestMetadata,
	contextDir?: string,
) {
	const title = meta.title ?? "";
	const body = meta.body ?? "";
	const branch = meta.source_branch;

	// Syntax matches are candidates, including examples and code spans. They establish neither a
	// provider-reported relationship nor adoption of another issue's criteria by the author.
	const refs = new Set<string>();
	collect(CLOSE_REF, `${title}\n${body}`, refs);
	collect(ISSUE_URL, `${title}\n${body}`, refs);
	const closingRefs = [...refs];

	const branchRefSet = new Set<string>();
	collect(BRANCH_REF, branch, branchRefSet);
	const branchRefs = [...branchRefSet];

	const keywordHits = (`${title}\n${body}`.match(CLOSE_KEYWORD) ?? []).length;
	CLOSE_KEYWORD.lastIndex = 0;

	const linked = unwrapLinkedItems(await readContextJson(contextDir, "linked_work_items.json"));
	const facts = linkedIssueFacts(linked);
	const hasCheckableAcBlock = facts.acHeading || facts.acBoxes > 0;

	const directions = [
		referenceDirection(closingRefs, branchRefs, keywordHits),
		linkedContextDirection(linked, facts),
	];

	return {
		hints: [],
		metrics: {
			issueReferenceSyntaxCandidateCount: closingRefs.length,
			hasIssueReferenceSyntaxCandidate: closingRefs.length > 0 ? 1 : 0,
			branchRefCount: branchRefs.length,
			hasBranchRef: branchRefs.length > 0 ? 1 : 0,
			closingKeywordHits: keywordHits,
			linkedItemsFilePresent: linked === null ? 0 : 1,
			linkedIssueCount: linked?.length ?? 0,
			linkedIssueBodyPresent: facts.bodyPresent ? 1 : 0,
			acceptanceCriteriaBlockPresent: hasCheckableAcBlock ? 1 : 0,
			acceptanceCriteriaCheckboxes: facts.acBoxes,
		},
		directions,
	};
}
