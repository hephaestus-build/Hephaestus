// Precompute FACTS for engaging-with-inline-review-comments: every inline comment by someone other
// than the author, one row each, with what the record shows beside it — the author's later reply in
// the thread, the thread's resolution and who resolved it, whether the change touches the line, and
// the authored commits that came after the comment, with those touching the same file counted
// apart. The review decides for each row whether the author engaged; the script never does.
import { readCommits } from "../lib/change.ts";
import {
	changeNear,
	commitsAfter,
	excerpt,
	readReviewComments,
	readReviewThreads,
	reviewerCommentRows,
} from "../lib/review.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

export default async function engagingWithInlineReviewComments(
	_repoPath: string,
	diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const author = metadata.author ?? "";
	const inline = await readReviewComments(contextDir);
	const record = await readReviewThreads(contextDir);
	const commits = await readCommits(contextDir);
	const rows = reviewerCommentRows(inline ?? [], record?.threads ?? [], author);
	const hints: Hint[] = rows.map(({ comment, replies, thread }) => ({
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
			fileInChange: diffFiles.has(comment.path),
			changeNearLine: changeNear(diffFiles.get(comment.path), comment.line),
			commitsAfter: commitsAfter(commits, comment.createdAt),
			commitsAfterTouchingFile: commitsAfter(commits, comment.createdAt, comment.path),
		},
	}));
	const byPeople = hints.filter((h) => h.flags.bot !== true);
	const replied = byPeople.filter((h) => h.flags.authorReplied === true).length;
	const committedAfter = byPeople.filter(
		(h) => Number(h.flags.commitsAfterTouchingFile) > 0,
	).length;
	const directions: string[] = [];
	if (inline === null) {
		directions.push(
			"No comments file was captured (comments.json): the inline record is not available here.",
		);
	} else if (hints.length === 0) {
		directions.push(
			`No inline comment by anyone other than the author among the ${String(inline.length)} captured; with no reviewer comment, the occasion did not arise.`,
		);
	} else {
		const bots = hints.length - byPeople.length;
		directions.push(
			`${String(byPeople.length)} reviewer comment(s)${bots > 0 ? ` (${String(bots)} more by bots)` : ""}; ${String(replied)} have a later author reply in the thread; ${String(committedAfter)} have a later authored commit touching the file. Commits are read from inputs/context/commits.json${commits.length === 0 ? ", which holds none here" : ""}.`,
			"A later commit touching the file says the file moved after the comment, not that the comment was taken up: read the comment and the changed lines. A RESOLVED mark says who clicked it.",
		);
	}
	return {
		hints,
		metrics: {
			commentsFileAbsent: inline === null ? 1 : 0,
			reviewerComments: byPeople.length,
			botComments: hints.length - byPeople.length,
			withAuthorReply: replied,
			withLaterCommitOnFile: committedAfter,
		},
		directions,
	};
}
