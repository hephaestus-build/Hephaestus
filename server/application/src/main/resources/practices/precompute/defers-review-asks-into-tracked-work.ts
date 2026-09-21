// Precompute FACTS for defers-review-asks-into-tracked-work: every ask a reviewer made, one row
// each, with what the record shows beside it — whether the change touches the file the ask is on,
// whether the author replied and what the reply names, whether the reviewer had a later word on it
// or approved after it, whether the thread was marked resolved. The review decides for each row
// whether the ask was addressed, deferred into tracked work, waived or merged past; a thread's
// RESOLVED mark is who clicked it, not what happened to the ask.
import { issueNumberReferences } from "../lib/references.ts";
import {
	type GeneralComment,
	type ReviewComment,
	readGeneralComments,
	readReviewComments,
	readReviewThreads,
} from "../lib/review.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

/** Words a reply uses to put an ask off rather than answer it. */
const DEFERRAL =
	/\b(?:later|next (?:mr|pr|merge request|pull request|sprint|iteration)|follow[- ]?up|will (?:do|add|fix|revisit|look)|todo|not (?:in|for) this (?:mr|pr)|separate (?:mr|pr|issue)|future)\b/iu;

/** Words a reviewer uses to drop an ask rather than keep it. */
const WAIVER =
	/\b(?:fine to skip|not needed|no need|never mind|nevermind|ignore|optional|nit|non[- ]blocking|up to you|can be skipped)\b/iu;

function excerpt(body: string): string {
	return body.replaceAll(/\s+/gu, " ").trim().slice(0, 160);
}

function later(a: string | undefined, b: string | undefined): boolean {
	return a !== undefined && b !== undefined && Date.parse(a) > Date.parse(b);
}

/** Whether the change adds or removes a line within `radius` lines of the ask's anchor. */
function changeNear(diff: DiffFile | undefined, line: number | undefined, radius = 15): boolean {
	if (diff === undefined || line === undefined) {
		return false;
	}
	const near = (n: number) => Math.abs(n - line) <= radius;
	return [...diff.addedLines.keys()].some(near) || [...diff.removedLines.keys()].some(near);
}

export default async function defersReviewAsksIntoTrackedWork(
	_repoPath: string,
	diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const author = metadata.author ?? "";
	const inline = await readReviewComments(contextDir);
	const general = await readGeneralComments(contextDir);
	const { threads, decisions } = await readReviewThreads(contextDir);
	const hints: Hint[] = [];
	const byOthers = (comment: { author?: string }) =>
		comment.author !== undefined && comment.author !== author;
	const approvedAfter = (at: string | undefined) =>
		decisions.some(
			(d) => d.state === "APPROVED" && d.dismissed !== true && later(d.submittedAt, at),
		);
	// An inline ask: the first comment on a line by someone other than the author. The author's
	// later comments on the same line are its thread's answers; the others' later comments there are
	// the reviewer's last word on it, not new asks.
	const inlineAsks = inline.filter(
		(c, index) =>
			byOthers(c) &&
			!inline.some(
				(earlier, j) =>
					j < index && byOthers(earlier) && earlier.path === c.path && earlier.line === c.line,
			),
	);
	for (const ask of inlineAsks) {
		const sameThread = (c: ReviewComment) =>
			c.path === ask.path && c.line === ask.line && later(c.createdAt, ask.createdAt);
		const replies = inline.filter((c) => c.author === author && sameThread(c));
		const followUps = inline.filter((c) => byOthers(c) && sameThread(c));
		hints.push(
			row(ask, replies, followUps, diffFiles, {
				threadResolved: threads.some(
					(t) => t.path === ask.path && t.line === ask.line && t.state === "RESOLVED",
				),
				approvedAfterAsk: approvedAfter(ask.createdAt),
			}),
		);
	}
	// A conversation ask: a general comment by someone other than the author; the author's later
	// general comments are the candidate answers, the others' later ones the follow-ups.
	for (const ask of general.filter(byOthers)) {
		const afterAsk = (c: GeneralComment) => later(c.createdAt, ask.createdAt);
		const replies = general.filter((c) => c.author === author && afterAsk(c));
		const followUps = general.filter((c) => byOthers(c) && afterAsk(c));
		hints.push(
			row({ ...ask, path: "" }, replies, followUps, diffFiles, {
				threadResolved: false,
				approvedAfterAsk: approvedAfter(ask.createdAt),
			}),
		);
	}
	const directions: string[] = [];
	if (hints.length === 0) {
		directions.push(
			"No comment by anyone other than the author was captured: with no ask, the occasion did not arise.",
		);
	} else {
		directions.push(
			`${String(hints.length)} comment(s) by others are listed, one row each; a remark that asks nothing of this change is not an ask. Decide every ask against the head diff: addressed when the diff carries the change asked for; deferred into tracked work when the author's reply names an issue or the description defers it to one; waived when the reviewer drops the ask in their own words, in the ask or in a follow-up, or approves after the author's stated reason; otherwise deferred bare. A RESOLVED thread says nothing by itself, and "approved on the condition that you do it next time" is a deferral to track, not a waiver.`,
			"A two-part ask is two asks: address one part and the other still stands.",
		);
	}
	return {
		hints,
		metrics: {
			asks: hints.length,
			inlineAsks: inlineAsks.length,
			conversationAsks: general.filter(byOthers).length,
			asksWithAuthorReply: hints.filter((h) => h.flags.authorReplied === true).length,
			asksNamingAnIssueInReply: hints.filter((h) => h.flags.issueNamedInReply === true).length,
		},
		directions,
	};
}

function row(
	ask: ReviewComment | (GeneralComment & { path: string }),
	replies: readonly { body: string }[],
	followUps: readonly { body: string }[],
	diffFiles: Map<string, DiffFile>,
	record: { threadResolved: boolean; approvedAfterAsk: boolean },
): Hint {
	const replyText = replies.map((r) => r.body).join("\n");
	const followUpText = followUps.map((r) => r.body).join("\n");
	const line = "line" in ask ? ask.line : undefined;
	const flags: Hint["flags"] = {
		by: ask.author ?? "",
		at: ask.createdAt ?? "",
		authorReplied: replies.length > 0,
		issueNamedInReply: issueNumberReferences(replyText).length > 0,
		deferralWordsInReply: DEFERRAL.test(replyText),
		waiverWordsInAsk: WAIVER.test(ask.body),
		reviewerFollowedUp: followUps.length > 0,
		waiverWordsInFollowUp: WAIVER.test(followUpText),
		approvedAfterAsk: record.approvedAfterAsk,
		threadResolved: record.threadResolved,
	};
	if (ask.path !== "") {
		flags.fileInChange = diffFiles.has(ask.path);
		flags.changeNearLine = changeNear(diffFiles.get(ask.path), line);
	}
	return {
		file: ask.path === "" ? "inputs/context/general_comments.json" : ask.path,
		line: line ?? 0,
		pattern: ask.path === "" ? "conversation ask" : "inline ask",
		context: excerpt(ask.body),
		inDiff: false,
		flags,
	};
}
