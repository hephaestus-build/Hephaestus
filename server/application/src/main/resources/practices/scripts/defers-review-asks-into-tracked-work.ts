import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import {
	issueReferences,
	later,
	scm,
	type DiffFile,
	type GeneralComment,
	type ReviewComment,
	type ReviewThread,
} from "@hephaestus/practice-scripts/scm";

export const LABELS = {
	"inline-ask":
		"A comment by someone other than the author that opens a thread on a line of the change.",
	"conversation-ask": "A comment by someone other than the author in the conversation.",
};

const DEFERRAL =
	/\b(?:later|next (?:mr|pr|merge request|pull request|sprint|iteration)|follow[- ]?up|will (?:do|add|fix|revisit|look)|todo|not (?:in|for) this (?:mr|pr)|separate (?:mr|pr|issue)|future)\b/iu;

const WAIVER =
	/\b(?:fine to skip|not needed|no need|never mind|nevermind|ignore|optional|nit|non[- ]blocking|up to you|can be skipped)\b/iu;

interface Ask {
	lead: ReviewLead;
	authorReplied: boolean;
	issueNamedInReply: boolean;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		if (ctx.review.origin === "REQUEST" && !work.item.is_merged) {
			return ctx.review({
				leads: [],
				directions: [
					"The work is not merged. This practice decides each review ask at the merge, so no ask is listed.",
				],
			});
		}
		const change = work.changedFiles();
		const author = work.item.author ?? "";
		const inline = await work.inlineComments();
		const general = await work.conversationComments();
		const record = await work.reviewRecord();
		const decisions = record?.decisions ?? [];
		const asks: Ask[] = [];
		const byOthers = (comment: { author?: string }) =>
			comment.author !== undefined && comment.author !== author;
		const approvedAfter = (at: string | undefined) =>
			decisions.some(
				(d) => d.state === "APPROVED" && d.dismissed !== true && later(d.submittedAt, at),
			);
		const inlineAsks = reviewerCommentRows(inline ?? [], record?.threads ?? [], author).filter(
			(r) => r.opensThread,
		);
		for (const ask of inlineAsks) {
			asks.push(
				askOf(ask.comment, ask.replies, ask.followUps, change, {
					threadResolved: ask.thread?.state === "RESOLVED",
					approvedAfterAsk: approvedAfter(ask.comment.createdAt),
				}),
			);
		}
		const conversationAsks = (general ?? []).filter(byOthers);
		for (const ask of conversationAsks) {
			const afterAsk = (c: GeneralComment) => later(c.createdAt, ask.createdAt);
			const replies = (general ?? []).filter((c) => c.author === author && afterAsk(c));
			const followUps = (general ?? []).filter((c) => byOthers(c) && afterAsk(c));
			asks.push(
				askOf(ask, replies, followUps, change, {
					threadResolved: false,
					approvedAfterAsk: approvedAfter(ask.createdAt),
				}),
			);
		}
		const directions: string[] = [];
		if (inline === null || general === null) {
			directions.push(
				"A required comments file is missing or malformed (comments.json or general_comments.json): report a collection gap and record no problem; an unread source does not establish that no ask exists.",
			);
		} else if (asks.length === 0) {
			directions.push(
				`No comment by anyone other than the author in the captured record (${inline.length} inline comment(s), ${general.length} conversation comment(s)): with no ask, the applicability did not arise.`,
			);
		} else {
			directions.push(
				`${String(asks.length)} comment(s) by others are listed, one lead each; a remark that asks nothing of this change is not an ask.`,
				"Decide every ask. One about the code is addressed when the head diff carries the change asked for.",
				"One about the pull request itself — mark it ready, retitle it, link an issue, fill in the description — is addressed when the captured record shows it done (metadata.json, description.md, the linked work items).",
				"A merged pull request was not a draft when it merged.",
				"An ask is deferred into tracked work when the author's reply names an issue or the description defers it to one; waived when the reviewer drops the ask in their own words, in the ask or in a follow-up; otherwise deferred bare.",
				'A later approval alone does not establish a waiver. A RESOLVED thread says nothing by itself, and "approved on the condition that you do it next time" is a deferral to track, not a waiver.',
				"A two-part ask is two asks: address one part and the other still stands.",
			);
		}
		return ctx.review({
			leads: asks.slice(0, 200).map((ask) => ask.lead),
			facts: {
				asks: asks.length,
				inlineAsks: inlineAsks.length,
				conversationAsks: conversationAsks.length,
				asksWithAuthorReply: asks.filter((ask) => ask.authorReplied).length,
				asksNamingAnIssueInReply: asks.filter((ask) => ask.issueNamedInReply).length,
				commentsFileAbsent: inline === null ? 1 : 0,
				generalCommentsFileAbsent: general === null ? 1 : 0,
			},
			directions,
		});
	},
});

function askOf(
	ask: ReviewComment | GeneralComment,
	replies: readonly { body: string }[],
	followUps: readonly { body: string }[],
	change: ReadonlyMap<string, DiffFile>,
	record: { threadResolved: boolean; approvedAfterAsk: boolean },
): Ask {
	const replyText = replies.map((r) => r.body).join("\n");
	const followUpText = followUps.map((r) => r.body).join("\n");
	const authorReplied = replies.length > 0;
	const issueNamedInReply = issueReferences(replyText).mentioned.length > 0;
	const facts = {
		by: ask.author ?? "",
		at: ask.createdAt ?? "",
		authorReplied,
		issueNamedInReply,
		deferralWordsInReply: DEFERRAL.test(replyText),
		waiverWordsInAsk: WAIVER.test(ask.body),
		reviewerFollowedUp: followUps.length > 0,
		waiverWordsInFollowUp: WAIVER.test(followUpText),
		approvedAfterAsk: record.approvedAfterAsk,
		threadResolved: record.threadResolved,
	};
	if (!("path" in ask)) {
		return {
			lead: {
				at: { record: "general_comments.json", line: ask.recordLine },
				label: LABELS["conversation-ask"],
				facts,
			},
			authorReplied,
			issueNamedInReply,
		};
	}
	return {
		lead: {
			at: { record: "comments.json", line: ask.recordLine },
			label: LABELS["inline-ask"],
			facts:
				ask.path === ""
					? facts
					: {
							...facts,
							path: ask.path,
							...(ask.line === undefined ? {} : { line: ask.line }),
							fileInChange: change.has(ask.path),
							changeNearLine: changeNear(change.get(ask.path), ask.line),
							outdated: ask.outdated,
						},
		},
		authorReplied,
		issueNamedInReply,
	};
}

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
				replies: inline.filter((c) => c.author === author && after(c)),
				followUps: inline.filter((c) => byOthers(c) && after(c)),
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
