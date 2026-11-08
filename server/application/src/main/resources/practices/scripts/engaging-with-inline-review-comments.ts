import { definePracticeScript, type Facts, type ReviewLead } from "@hephaestus/practice-scripts";
import {
	later,
	scm,
	type ChangeCommit,
	type DiffFile,
	type GeneralComment,
	type ReviewComment,
	type ReviewDecision,
	type ReviewThread,
} from "@hephaestus/practice-scripts/scm";

export const LABELS = {
	"reviewer-comment": "An inline comment by someone other than the author.",
	"conversation-comment": "A conversation comment by someone other than the author.",
};

function between(
	at: string | undefined,
	from: string | undefined,
	to: string | undefined,
): boolean {
	return later(at, from) && !later(at, to);
}

// Approval alongside advice differs from approval after the author responded.
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
				d.quotesHephaestusMarker !== true &&
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

interface Note {
	lead: ReviewLead;
	bot: boolean;
	quotesHephaestusMarker: boolean;
	replied: boolean;
	committedOnFile: boolean;
	afterHandOff: boolean;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const change = work.changedFiles();
		const author = work.item.author ?? "";
		const inline = await work.inlineComments();
		const general = await work.conversationComments();
		const record = await work.reviewRecord();
		const capturedCommits = await work.commits();
		const commits = capturedCommits ?? [];
		const decisions = record?.decisions ?? [];
		const handOff = work.item.merged_at ?? work.item.closed_at;
		const byOthers = (note: { author?: string }) =>
			note.author !== undefined && note.author !== author;
		const authorNotes = [
			...(inline ?? []).filter((c) => c.author === author && c.quotesHephaestusMarker !== true),
			...(general ?? []).filter((c) => c.author === author && c.quotesHephaestusMarker !== true),
		];
		const reviewerNextNote = (reviewer: string | undefined, at: string | undefined) =>
			(general ?? []).find(
				(c) => c.author === reviewer && c.quotesHephaestusMarker !== true && later(c.createdAt, at),
			)?.body ?? "";

		const inlineNotes: Note[] = reviewerCommentRows(
			inline ?? [],
			record?.threads ?? [],
			author,
		).map(({ comment, replies, followUps, thread }) => {
			const commitsAfterTouchingFile =
				capturedCommits === null
					? "unknown"
					: commitsAfter(commits, comment.createdAt, comment.path);
			const afterHandOff = later(comment.createdAt, handOff);
			const facts: Facts = {
				by: comment.author ?? "",
				bot: comment.bot,
				...(comment.quotesHephaestusMarker === true ? { quotesHephaestusMarker: true } : {}),
				at: comment.createdAt ?? "",
				path: comment.path,
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
				afterHandOff,
				fileInChange: change.has(comment.path),
				changeNearLine: changeNear(change.get(comment.path), comment.line),
				commitsAfter:
					capturedCommits === null ? "unknown" : commitsAfter(commits, comment.createdAt),
				commitsAfterTouchingFile,
			};
			if (comment.line !== undefined) {
				facts.line = comment.line;
			}
			return {
				lead: {
					at: { record: "comments.json", line: comment.recordLine },
					label: LABELS["reviewer-comment"],
					facts,
				},
				bot: comment.bot,
				quotesHephaestusMarker: comment.quotesHephaestusMarker === true,
				replied: replies.length > 0,
				committedOnFile: Number(commitsAfterTouchingFile) > 0,
				afterHandOff,
			};
		});
		const conversationNotes: Note[] = (general ?? [])
			.filter(byOthers)
			.map((note: GeneralComment) => {
				const authorAfter = (general ?? []).filter(
					(c) =>
						c.author === author &&
						c.quotesHephaestusMarker !== true &&
						later(c.createdAt, note.createdAt),
				);
				const afterHandOff = later(note.createdAt, handOff);
				return {
					lead: {
						at: { record: "general_comments.json", line: note.recordLine },
						label: LABELS["conversation-comment"],
						facts: {
							by: note.author ?? "",
							bot: note.bot,
							...(note.quotesHephaestusMarker === true ? { quotesHephaestusMarker: true } : {}),
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
							afterHandOff,
							commitsAfter:
								capturedCommits === null ? "unknown" : commitsAfter(commits, note.createdAt),
						},
					},
					bot: note.bot,
					quotesHephaestusMarker: note.quotesHephaestusMarker === true,
					replied: authorAfter.length > 0,
					committedOnFile: false,
					afterHandOff,
				};
			});
		const notes = [...inlineNotes, ...conversationNotes];
		const byPerson = (note: Note) => !note.bot && !note.quotesHephaestusMarker;
		const byPeople = notes.filter(byPerson);
		const inlineByPeople = inlineNotes.filter(byPerson).length;
		const conversationByPeople = conversationNotes.filter(byPerson).length;
		const uncertainOrigin = notes.filter((note) => note.quotesHephaestusMarker).length;
		const bots = notes.filter((note) => note.bot && !note.quotesHephaestusMarker).length;
		const replied = byPeople.filter((note) => note.replied).length;
		// Without captured commits, reply counts and their sum are unknown, not zero.
		const committedAfter =
			capturedCommits === null ? null : byPeople.filter((note) => note.committedOnFile).length;
		const afterHandOff = byPeople.filter((note) => note.afterHandOff).length;
		const directions: string[] = [];
		if (inline === null && general === null) {
			directions.push(
				"No comments file was captured (neither comments.json nor general_comments.json): the record of reviewer notes is not available here.",
			);
		} else if (notes.length === 0) {
			directions.push(
				`No note by anyone other than the author in the captured record (${inline === null ? "comments.json not captured" : `${String(inline.length)} inline comment(s)`}, ${general === null ? "general_comments.json not captured" : `${String(general.length)} conversation comment(s)`}).`,
			);
		} else {
			directions.push(
				`${String(byPeople.length)} reviewer note(s) (${String(inlineByPeople)} inline, ${String(conversationByPeople)} conversation)${bots > 0 ? `, ${String(bots)} more by bots` : ""}${uncertainOrigin > 0 ? `, ${String(uncertainOrigin)} more with uncertain authorship (retained Hephaestus markers)` : ""}; ${String(replied)} have a later author note.`,
				`${committedAfter === null ? "How many have a later authored commit touching the commented file is unknown" : `${String(committedAfter)} have a later authored commit touching the commented file`}; ${String(afterHandOff)} were posted after the work merged or closed.`,
				`Commit capture: ${capturedCommits === null ? "unavailable" : `${String(commits.length)} captured commit(s)`} in commits.json.`,
			);
		}
		return ctx.review({
			leads: notes.slice(0, 200).map((note) => note.lead),
			facts: {
				commentsFileAbsent: inline === null ? 1 : 0,
				generalCommentsFileAbsent: general === null ? 1 : 0,
				...(inline === null ? {} : { reviewerComments: inlineByPeople }),
				...(general === null ? {} : { conversationComments: conversationByPeople }),
				...(inline === null && general === null
					? {}
					: {
							botComments: bots,
							...(uncertainOrigin > 0 ? { uncertainOriginComments: uncertainOrigin } : {}),
							withAuthorReply: replied,
							...(committedAfter === null ? {} : { withLaterCommitOnFile: committedAfter }),
							afterHandOff,
						}),
				commitsFileAbsent: capturedCommits === null ? 1 : 0,
			},
			directions,
		});
	},
});

interface ReviewerCommentRow {
	comment: ReviewComment;
	/** No earlier comment by others in the same thread: this one opens it. */
	opensThread: boolean;
	replies: ReviewComment[];
	followUps: ReviewComment[];
	thread?: ReviewThread;
}

function sameThread(a: ReviewComment, b: ReviewComment): boolean {
	return a.thread !== undefined && b.thread !== undefined
		? a.thread === b.thread
		: a.path === b.path && a.line === b.line;
}

function reviewerCommentRows(
	inline: readonly ReviewComment[],
	threads: readonly ReviewThread[],
	author: string,
): ReviewerCommentRow[] {
	const byOthers = (c: ReviewComment) => c.author !== undefined && c.author !== author;
	const threadOf = (c: ReviewComment) =>
		threads.find((t) =>
			c.thread !== undefined && t.id !== undefined
				? t.id === c.thread
				: t.path === c.path && t.line === c.line,
		);
	return inline.flatMap((comment, index) => {
		if (!byOthers(comment)) {
			return [];
		}
		const after = (c: ReviewComment) =>
			sameThread(c, comment) && later(c.createdAt, comment.createdAt);
		return [
			{
				comment,
				opensThread: !inline.some(
					(earlier, j) => j < index && byOthers(earlier) && sameThread(earlier, comment),
				),
				replies: inline.filter(
					(c) => c.author === author && c.quotesHephaestusMarker !== true && after(c),
				),
				followUps: inline.filter(
					(c) => byOthers(c) && c.quotesHephaestusMarker !== true && after(c),
				),
				thread: threadOf(comment),
			},
		];
	});
}

function changeNear(diff: DiffFile | undefined, line: number | undefined, radius = 15): boolean {
	if (diff === undefined || line === undefined) {
		return false;
	}
	const near = (n: number) => Math.abs(n - line) <= radius;
	return [...diff.addedLines.keys()].some(near) || [...diff.removedLines.keys()].some(near);
}

function excerpt(body: string, length = 160): string {
	return body.replaceAll(/\s+/gu, " ").trim().slice(0, length);
}

function commitsAfter(
	commits: readonly ChangeCommit[],
	at: string | undefined,
	path?: string,
): number {
	return commits.filter(
		(c) =>
			c.parents.length < 2 &&
			later(c.committedAt, at) &&
			(path === undefined || c.files.some((f) => f.path === path || f.oldPath === path)),
	).length;
}

/** Milliseconds between two ISO instants, or null when either is missing or unreadable. */
function millisBetween(from: string | undefined, to: string | undefined): number | null {
	if (from === undefined || to === undefined) {
		return null;
	}
	const a = Date.parse(from);
	const b = Date.parse(to);
	return Number.isNaN(a) || Number.isNaN(b) ? null : b - a;
}
