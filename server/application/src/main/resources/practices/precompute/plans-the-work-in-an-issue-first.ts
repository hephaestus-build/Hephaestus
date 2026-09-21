// Precompute FACTS for plans-the-work-in-an-issue-first: when each issue the change adopts was
// opened, when the change's earliest commit was authored, and the time between them. The practice
// asks one question of two timestamps; the script puts both beside each other so the review reads
// them rather than reasons around them.
import { readCommits } from "../lib/change.ts";
import { text } from "../lib/practice-contract.ts";
import {
	branchIssueReferences,
	closingReferences,
	issueNumberReferences,
} from "../lib/references.ts";
import { millisBetween, readLinkedWorkItems } from "../lib/review.ts";
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
	contextDir?: string,
) {
	const hints: Hint[] = [];
	const directions: string[] = [];
	const body = text(metadata.body);
	const title = text(metadata.title);
	// The issue the change adopts: named with a closing keyword, in the title, or in the branch.
	const adopted = new Set<number>([
		...closingReferences(body),
		...issueNumberReferences(title),
		...branchIssueReferences(metadata.source_branch),
	]);
	const linked = (await readLinkedWorkItems(contextDir)) ?? [];
	const commits = await readCommits(contextDir);
	const authored = commits
		.filter((commit) => commit.authoredAt !== "")
		.toSorted((a, b) => Date.parse(a.authoredAt) - Date.parse(b.authoredAt));
	const first = authored[0];
	const prCreatedAt = text(metadata.created_at);
	let earliestDeltaMinutes = Number.NaN;
	for (const item of linked) {
		if (!adopted.has(item.number)) {
			continue;
		}
		const toFirstCommit = millisBetween(item.createdAt, first?.authoredAt);
		const toPullRequest = millisBetween(item.createdAt, prCreatedAt || undefined);
		const flags: Hint["flags"] = { number: item.number, openedAt: item.createdAt ?? "" };
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
		const relation = relationOf(toFirstCommit);
		hints.push({
			file: `inputs/context/linked_work_items/${String(item.number)}.md`,
			line: 0,
			pattern: "adopted issue",
			context: `#${String(item.number)} opened ${item.createdAt ?? "(unknown)"}; ${relation}`,
			inDiff: false,
			flags,
		});
	}
	if (hints.length === 0) {
		directions.push(
			adopted.size === 0
				? "No issue is adopted by a closing keyword, the title or the branch; without an adopted issue there is nothing to have planned in."
				: `The adopted issue(s) ${[...adopted].map((n) => `#${String(n)}`).join(", ")} were not captured among the linked work items; the opening time is not available here.`,
		);
	} else {
		directions.push(
			"The cell is decided by the issue's opening time against the earliest authored commit of the change (inputs/context/commits.json), never against the pull request's creation: a pull request is opened when the work is handed off, and the practice asks whether the plan preceded the work.",
			"An earliest commit authored before the issue was opened is work that began without the plan, whatever the commit contains; the size of the gap goes to the severity, not to the cell.",
		);
	}
	return {
		hints,
		metrics: {
			adoptedIssues: adopted.size,
			linkedItems: linked.length,
			commits: commits.length,
			issueOpenedAfterFirstCommitMinutes: Number.isNaN(earliestDeltaMinutes)
				? -1
				: Math.max(0, Math.round(earliestDeltaMinutes)),
		},
		directions,
	};
}
