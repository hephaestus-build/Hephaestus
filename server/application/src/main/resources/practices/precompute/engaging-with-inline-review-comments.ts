// Precompute FACTS for engaging-with-inline-review-comments: every note by someone other than the
// author, inline and in the conversation, one row each, with what the record shows beside it — the
// author's later reply, the thread's resolution and who resolved it, the reviewer's own later note,
// whether and how soon the same reviewer approved afterwards, whether the note came after the work
// merged or closed, and the authored commits that came after it. The review decides for each row
// whether the note asked something of this change and whether the author engaged; the script never does.
import { type ChangeCommit, readCapturedCommits } from "../lib/change.ts";
import { contextFile } from "../lib/context.ts";
import {
	type GeneralComment,
	type ReviewDecision,
	changeNear,
	commitsAfter,
	excerpt,
	later,
	millisBetween,
	readGeneralComments,
	readReviewComments,
	readReviewThreads,
	reviewerCommentRows,
} from "../lib/review.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

/** An instant strictly between two others; false when any is missing. */
function between(
	at: string | undefined,
	from: string | undefined,
	to: string | undefined,
): boolean {
	return later(at, from) && !later(at, to);
}

/**
 * The same reviewer's first approval at or after the note, with what came between them: advice posted
 * with an approval reads differently from an approval given after the author answered. Empty when that
 * reviewer did not approve afterwards.
 */
function approvalAfter(
	decisions: readonly ReviewDecision[],
	reviewer: string | undefined,
	at: string | undefined,
	commits: readonly ChangeCommit[] | null,
	authorNotes: readonly { createdAt?: string }[],
): string {
	const approval = decisions
		.filter(
			(d) =>
				d.state === "APPROVED" &&
				d.dismissed !== true &&
				d.author === reviewer &&
				(millisBetween(at, d.submittedAt) ?? -1) >= 0,
		)
		.toSorted((a, b) => Date.parse(a.submittedAt ?? "") - Date.parse(b.submittedAt ?? ""))[0];
	if (approval === undefined) {
		return "";
	}
	const seconds = Math.round((millisBetween(at, approval.submittedAt) ?? 0) / 1000);
	const commitsBetween = (commits ?? []).filter(
		(c) => c.parents.length < 2 && between(c.committedAt, at, approval.submittedAt),
	).length;
	const notesBetween = authorNotes.filter((n) =>
		between(n.createdAt, at, approval.submittedAt),
	).length;
	return `${String(seconds)}s later; ${commits === null ? "unknown commit count" : `${String(commitsBetween)} commit(s)`} and ${String(notesBetween)} author note(s) between`;
}

export default async function engagingWithInlineReviewComments(
	_repoPath: string,
	diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir: string | undefined,
	_changeDir: string | undefined,
	contextReference: string,
) {
	const author = metadata.author ?? "";
	const inline = await readReviewComments(contextDir);
	const general = await readGeneralComments(contextDir);
	const record = await readReviewThreads(contextDir);
	const capturedCommits = await readCapturedCommits(contextDir);
	const commits = capturedCommits ?? [];
	const decisions = record?.decisions ?? [];
	const handOff = metadata.merged_at ?? metadata.closed_at;
	const byOthers = (note: { author?: string }) =>
		note.author !== undefined && note.author !== author;
	const authorNotes = [
		...(inline ?? []).filter((c) => c.author === author),
		...(general ?? []).filter((c) => c.author === author),
	];
	/** The reviewer's next conversation note after `at`, where a settling "thank you" usually sits. */
	const reviewerNextNote = (reviewer: string | undefined, at: string | undefined) =>
		(general ?? []).find((c) => c.author === reviewer && later(c.createdAt, at))?.body ?? "";

	const inlineHints: Hint[] = reviewerCommentRows(inline ?? [], record?.threads ?? [], author).map(
		({ comment, replies, followUps, thread }) => ({
			file: comment.path,
			line: comment.line ?? 0,
			pattern: "reviewer comment",
			context: excerpt(comment.body),
			inDiff: false,
			flags: {
				by: comment.author ?? "",
				bot: comment.bot,
				at: comment.createdAt ?? "",
				side: comment.side ?? "",
				outdated: comment.outdated,
				authorReplied: replies.length > 0,
				replyExcerpt: excerpt(replies.map((r) => r.body).join(" "), 80),
				threadResolved: thread?.state === "RESOLVED",
				resolvedBy: thread?.resolvedBy ?? "",
				reviewerLaterNote: excerpt(
					followUps.find((f) => f.author === comment.author)?.body ??
						reviewerNextNote(comment.author, comment.createdAt),
					80,
				),
				reviewerApprovedAfter: approvalAfter(
					decisions,
					comment.author,
					comment.createdAt,
					capturedCommits,
					authorNotes,
				),
				afterHandOff: later(comment.createdAt, handOff),
				fileInChange: diffFiles.has(comment.path),
				changeNearLine: changeNear(diffFiles.get(comment.path), comment.line),
				commitsAfter:
					capturedCommits === null ? "unknown" : commitsAfter(commits, comment.createdAt),
				commitsAfterTouchingFile:
					capturedCommits === null
						? "unknown"
						: commitsAfter(commits, comment.createdAt, comment.path),
			},
		}),
	);
	const conversationHints: Hint[] = (general ?? []).filter(byOthers).map((note: GeneralComment) => {
		const authorAfter = (general ?? []).filter(
			(c) => c.author === author && later(c.createdAt, note.createdAt),
		);
		return {
			file: contextFile(contextReference, "general_comments.json"),
			line: 0,
			pattern: "conversation comment",
			context: excerpt(note.body),
			inDiff: false,
			flags: {
				by: note.author ?? "",
				bot: note.bot,
				at: note.createdAt ?? "",
				authorNotesAfter: authorAfter.length,
				authorNoteExcerpt: excerpt(authorAfter[0]?.body ?? "", 80),
				reviewerLaterNote: excerpt(reviewerNextNote(note.author, note.createdAt), 80),
				reviewerApprovedAfter: approvalAfter(
					decisions,
					note.author,
					note.createdAt,
					capturedCommits,
					authorNotes,
				),
				afterHandOff: later(note.createdAt, handOff),
				commitsAfter: capturedCommits === null ? "unknown" : commitsAfter(commits, note.createdAt),
			},
		};
	});
	const hints = [...inlineHints, ...conversationHints];
	const byPeople = hints.filter((h) => h.flags.bot !== true);
	const replied = byPeople.filter(
		(h) => h.flags.authorReplied === true || Number(h.flags.authorNotesAfter) > 0,
	).length;
	// Without the commit record each note's count is unknown, and so is their sum: never a measured 0.
	const committedAfter =
		capturedCommits === null
			? null
			: byPeople.filter((h) => Number(h.flags.commitsAfterTouchingFile) > 0).length;
	const afterHandOff = byPeople.filter((h) => h.flags.afterHandOff === true).length;
	const directions: string[] = [];
	if (inline === null && general === null) {
		directions.push(
			"No comments file was captured (neither comments.json nor general_comments.json): the record of reviewer notes is not available here.",
		);
	} else if (hints.length === 0) {
		directions.push(
			`No note by anyone other than the author in the captured record (${inline === null ? "comments.json not captured" : `${String(inline.length)} inline comment(s)`}, ${general === null ? "general_comments.json not captured" : `${String(general.length)} conversation comment(s)`}).`,
		);
	} else {
		const bots = hints.length - byPeople.length;
		directions.push(
			`${String(byPeople.length)} reviewer note(s) (${String(inlineHints.filter((h) => h.flags.bot !== true).length)} inline, ${String(conversationHints.filter((h) => h.flags.bot !== true).length)} conversation)${bots > 0 ? `, ${String(bots)} more by bots` : ""}; ${String(replied)} have a later author note; ${committedAfter === null ? "how many have a later authored commit touching the commented file is unknown" : `${String(committedAfter)} have a later authored commit touching the commented file`}; ${String(afterHandOff)} were posted after the work merged or closed. Commit capture: ${capturedCommits === null ? "unavailable" : `${String(commits.length)} captured commit(s)`} in ${contextFile(contextReference, "commits.json")}.`,
		);
	}
	return {
		hints,
		metrics: {
			commentsFileAbsent: inline === null ? 1 : 0,
			generalCommentsFileAbsent: general === null ? 1 : 0,
			reviewerComments: inlineHints.filter((h) => h.flags.bot !== true).length,
			conversationComments: conversationHints.filter((h) => h.flags.bot !== true).length,
			botComments: hints.length - byPeople.length,
			withAuthorReply: replied,
			...(committedAfter === null ? {} : { withLaterCommitOnFile: committedAfter }),
			afterHandOff,
			commitsFileAbsent: capturedCommits === null ? 1 : 0,
		},
		directions,
	};
}
