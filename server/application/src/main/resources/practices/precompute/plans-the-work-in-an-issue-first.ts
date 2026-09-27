// Precompute FACTS for plans-the-work-in-an-issue-first: when each issue the change names as its own
// was opened, when the change's earliest commit was authored, and the time between them. The practice
// asks one question of two timestamps; the script puts both beside each other so the review reads
// them rather than reasons around them.
import { readCommits } from "../lib/change.ts";
import { contextFile } from "../lib/context.ts";
import { text } from "../lib/practice-contract.ts";
import {
	branchIssueReferences,
	closingReferences,
	issueNumberReferences,
	relatedReferences,
} from "../lib/references.ts";
import { millisBetween, readLinkedWorkItemCapture } from "../lib/review.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

/** The two moments in one sentence, the way the cell reads them. */
function relationOf(issueToFirstCommit: number | null): string {
	if (issueToFirstCommit === null) {
		return "the earliest commit's authored time is not available";
	}
	return issueToFirstCommit >= 0
		? `the earliest commit was authored ${hours(issueToFirstCommit)} h after the issue was opened`
		: `the issue was opened ${hours(-issueToFirstCommit)} h after the earliest commit was authored`;
}

/** Hours with one decimal, signed: positive when `to` is later than `from`. */
function hours(millis: number): string {
	return (millis / 3_600_000).toFixed(1);
}

export default async function plansTheWorkInAnIssueFirst(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir: string | undefined,
	_changeDir: string | undefined,
	contextReference: string,
) {
	const hints: Hint[] = [];
	const directions: string[] = [];
	const body = text(metadata.body);
	const title = text(metadata.title);
	const capture = await readLinkedWorkItemCapture(contextDir);
	const linked = capture?.items ?? null;
	const named = new Map<number, string[]>();
	const name = (how: string, numbers: number[]) => {
		for (const n of numbers) {
			named.set(n, [...(named.get(n) ?? []), how]);
		}
	};
	name(
		"provider closing link",
		(linked ?? []).filter((item) => item.how === "closesOnMerge").map((item) => item.number),
	);
	name("closing keyword", closingReferences(body));
	name("title", issueNumberReferences(title));
	name("branch", branchIssueReferences(metadata.source_branch));
	name("Related to", relatedReferences(body));
	const commits = await readCommits(contextDir);
	const authored = commits
		.filter((commit) => commit.authoredAt !== "")
		.toSorted((a, b) => Date.parse(a.authoredAt) - Date.parse(b.authoredAt));
	const first = authored[0];
	const prCreatedAt = text(metadata.created_at);
	let earliestDeltaMinutes = Number.NaN;
	for (const item of linked ?? []) {
		const namedBy = named.get(item.number)?.join(", ");
		if (namedBy === undefined) {
			continue;
		}
		const toFirstCommit = millisBetween(item.createdAt, first?.authoredAt);
		const toPullRequest = millisBetween(item.createdAt, prCreatedAt || undefined);
		const flags: Hint["flags"] = {
			number: item.number,
			namedBy,
			providerLink: item.how ?? "",
			openedAt: item.createdAt ?? "",
		};
		if (first !== undefined) {
			flags.firstCommit = first.sha.slice(0, 7);
			flags.firstCommitAuthoredAt = first.authoredAt;
		}
		if (toFirstCommit !== null) {
			flags.hoursFromIssueToFirstCommit = hours(toFirstCommit);
			const minutes = -toFirstCommit / 60_000;
			if (Number.isNaN(earliestDeltaMinutes) || minutes > earliestDeltaMinutes) {
				earliestDeltaMinutes = minutes;
			}
		}
		if (toPullRequest !== null) {
			flags.hoursFromIssueToPullRequest = hours(toPullRequest);
		}
		const relation =
			item.createdAt === undefined
				? "its opening time is not captured, so the order cannot be read"
				: relationOf(toFirstCommit);
		hints.push({
			file: contextFile(contextReference, `linked_work_items/${String(item.number)}.md`),
			line: 0,
			pattern: "candidate issue",
			context: `#${String(item.number)} (named by ${namedBy}; captured as ${item.how ?? "an unclassified link"}) opened ${item.createdAt ?? "(unknown)"}; ${relation}`,
			inDiff: false,
			flags,
		});
	}
	const numbers = [...named.keys()].map((n) => `#${String(n)}`).join(", ");
	// A named number this repository holds no synchronized issue for may be an issue not yet
	// synchronized or a reference elsewhere; the record cannot say which.
	const unresolved = [...named.keys()].filter((n) => capture?.unresolved.includes(n) ?? false);
	if (capture === null) {
		directions.push(
			`${contextFile(contextReference, "linked_work_items.json")} was not captured: a collection gap, not a change without an issue.`,
		);
	} else if (hints.length === 0 && unresolved.length === 0) {
		directions.push(
			"No issue of this repository is named by a provider closing link, a closing keyword, the title, the branch or a `Related to #N`; without an issue this change implements there is nothing to have planned in.",
		);
	} else {
		if (unresolved.length > 0) {
			directions.push(
				`The named issue(s) ${unresolved.map((n) => `#${String(n)}`).join(", ")} are among the capture's unresolved references: this repository holds no synchronized issue with that number, so no opening time. Whether one is the issue this change implements is read from the description: if it is, the order stays open (UNDETERMINED, never a timing judgement on another issue instead); if the description rules it out, it is no occasion.`,
			);
		}
		if (hints.length > 0) {
			directions.push(
				`The cell is decided by the issue's opening time against the earliest authored commit of the change (${contextFile(contextReference, "commits.json")}), never against the pull request's creation: a pull request is opened when the work is handed off, and the practice asks whether the plan preceded the work.`,
				"An earliest commit authored before the issue was opened is work that began without the plan, whatever the commit contains; the size of the gap goes to the severity, not to the cell.",
			);
			if (hints.some((hint) => hint.flags.namedBy === "Related to")) {
				directions.push(
					"An issue named only by `Related to` is the occasion only when the description or the issue shows this change implements part of it, not when the sentence is about other work. It was captured as a mention, not a closing candidate: nothing here says the issue closed or will close on merge.",
				);
			}
			if (hints.length > 1) {
				directions.push(
					`Several issues are named (${numbers}): judge the one this change implements, read from the description and the issues. If more than one remains plausible and their timing falls in different cells, do not choose the favourable one.`,
				);
			}
		}
	}
	return {
		hints,
		metrics: {
			namedIssues: named.size,
			unresolvedNamedIssues: unresolved.length,
			linkedItems: linked?.length ?? -1,
			commits: commits.length,
			issueOpenedAfterFirstCommitMinutes: Number.isNaN(earliestDeltaMinutes)
				? -1
				: Math.max(0, Math.round(earliestDeltaMinutes)),
		},
		directions,
	};
}
