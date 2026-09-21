// Precompute FACTS for defers-review-asks-into-tracked-work: every ask a reviewer made, one row
// each, with what the record shows beside it — whether the change touches the file the ask is on,
// whether the author replied and what the reply names, whether the thread was marked resolved. The
// review decides for each row whether the ask was addressed, deferred into tracked work, waived or
// merged past; a thread's RESOLVED mark is who clicked it, not what happened to the ask.
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
	const { threads } = await readReviewThreads(contextDir);
	const hints: Hint[] = [];
	const byOthers = (comment: { author?: string }) =>
		comment.author !== undefined && comment.author !== author;
	// An inline ask: a comment on a line by someone other than the author. The author's later
	// comments on the same line are its thread's answers.
	for (const ask of inline.filter(byOthers)) {
		const replies = inline.filter(
			(c) =>
				c.author === author &&
				c.path === ask.path &&
				c.line === ask.line &&
				later(c.createdAt, ask.createdAt),
		);
		hints.push(
			row(
				ask,
				replies,
				threads.some((t) => t.path === ask.path && t.line === ask.line && t.state === "RESOLVED"),
				diffFiles,
			),
		);
	}
	// A conversation ask: a general comment by someone other than the author; the author's later
	// general comments are the candidate answers.
	for (const ask of general.filter(byOthers)) {
		const replies = general.filter((c) => c.author === author && later(c.createdAt, ask.createdAt));
		hints.push(row({ ...ask, path: "" }, replies, false, diffFiles));
	}
	const directions: string[] = [];
	if (hints.length === 0) {
		directions.push(
			"No comment by anyone other than the author was captured: with no ask, the occasion did not arise.",
		);
	} else {
		directions.push(
			`${String(hints.length)} ask(s) by others are listed, one row each. Decide every row against the head diff: addressed when the diff carries the change asked for; deferred into tracked work when the author's reply names an issue or the description defers it to one; waived when the reviewer drops the ask in their own words; otherwise deferred bare. A RESOLVED thread and an approval say nothing by themselves, and "approved on the condition that you do it next time" is a deferral to track, not a waiver.`,
			"A two-part ask is two asks: address one part and the other still stands.",
		);
	}
	return {
		hints,
		metrics: {
			asks: hints.length,
			inlineAsks: inline.filter(byOthers).length,
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
	resolved: boolean,
	diffFiles: Map<string, DiffFile>,
): Hint {
	const replyText = replies.map((r) => r.body).join("\n");
	const line = "line" in ask ? ask.line : undefined;
	const flags: Hint["flags"] = {
		by: ask.author ?? "",
		at: ask.createdAt ?? "",
		authorReplied: replies.length > 0,
		issueNamedInReply: issueNumberReferences(replyText).length > 0,
		deferralWordsInReply: DEFERRAL.test(replyText),
		waiverWordsInAsk: WAIVER.test(ask.body),
		threadResolved: resolved,
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
