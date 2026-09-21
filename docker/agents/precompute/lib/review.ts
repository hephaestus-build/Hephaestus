/**
 * Read-only helpers for the review record the server projects beside a change: the linked issues,
 * the inline and general comments, the review threads and decisions. Each reader returns what the
 * file carries in the shape the server wrote it, best effort. A file the capture did not write is
 * `null`, never an empty list: "no comments file was captured" and "no comment by others" are two
 * different facts, and a script says which one it found.
 */

import type { ChangeCommit } from "./change.ts";
import { readContextJson } from "./context.ts";
import { isJsonObject, text } from "./practice-contract.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "./types.ts";

export interface LinkedWorkItem {
	number: number;
	title: string;
	state?: string;
	/** `closes` when the provider records the pull request as closing the issue; `mentions` otherwise. */
	how?: string;
	body: string;
	createdAt?: string;
	closedAt?: string;
	subIssuesTotal?: number;
	subIssuesCompleted?: number;
}

/** A comment anchored to a line of the change (`comments.json`). */
export interface ReviewComment {
	id?: number;
	/** The `id` of the thread in `review_threads.json` this comment belongs to. */
	thread?: number;
	/** The `id` of the comment this one answers. */
	inReplyTo?: number;
	path: string;
	line?: number;
	/** LEFT for a line of the base, RIGHT for a line of the head. */
	side?: string;
	outdated: boolean;
	body: string;
	author?: string;
	/** The provider classified the author as automation (an access token, an app, a service user). */
	bot: boolean;
	createdAt?: string;
}

/** A comment in the conversation, not on a line (`general_comments.json`). */
export interface GeneralComment {
	body: string;
	author?: string;
	bot: boolean;
	createdAt?: string;
}

export interface ReviewThread {
	id?: number;
	path?: string;
	line?: number;
	state: string;
	resolvedBy?: string;
	/** When the provider recorded the resolution; absent when unresolved or when only a sync saw it. */
	resolvedAt?: string;
	outdated?: boolean;
	createdAt?: string;
}

export interface ReviewDecision {
	state: string;
	author?: string;
	bot: boolean;
	submittedAt?: string;
	body?: string;
	dismissed?: boolean;
}

function optionalString(value: unknown): string | undefined {
	return typeof value === "string" && value !== "" ? value : undefined;
}

function optionalNumber(value: unknown): number | undefined {
	return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function objects(value: unknown): Record<string, unknown>[] {
	return Array.isArray(value) ? value.filter(isJsonObject) : [];
}

/** The issues the change names, as `linked_work_items.json` records them; null when not captured. */
export async function readLinkedWorkItems(
	contextDir: string | undefined,
): Promise<LinkedWorkItem[] | null> {
	const parsed = await readContextJson(contextDir, "linked_work_items.json");
	if (parsed === null) {
		return null;
	}
	const items = isJsonObject(parsed) ? objects(parsed.workItems) : objects(parsed);
	return items.flatMap((item) => {
		const number = optionalNumber(item.number);
		if (number === undefined) {
			return [];
		}
		return [
			{
				number,
				title: text(item.title),
				state: optionalString(item.state),
				how: optionalString(item.how),
				body: text(item.body ?? item.description),
				createdAt: optionalString(item.createdAt),
				closedAt: optionalString(item.closedAt),
				subIssuesTotal: optionalNumber(item.subIssuesTotal),
				subIssuesCompleted: optionalNumber(item.subIssuesCompleted),
			},
		];
	});
}

/** The inline comments, oldest first; null when `comments.json` was not captured. */
export async function readReviewComments(
	contextDir: string | undefined,
): Promise<ReviewComment[] | null> {
	const parsed = await readContextJson(contextDir, "comments.json");
	if (parsed === null) {
		return null;
	}
	const list = isJsonObject(parsed) ? objects(parsed.comments) : objects(parsed);
	return list.map((comment) => ({
		id: optionalNumber(comment.id),
		thread: optionalNumber(comment.thread),
		inReplyTo: optionalNumber(comment.in_reply_to),
		path: text(comment.path),
		line: optionalNumber(comment.line),
		side: optionalString(comment.side),
		outdated: comment.outdated === true,
		body: text(comment.body),
		author: optionalString(comment.author),
		bot: comment.bot === true,
		createdAt: optionalString(comment.created_at ?? comment.createdAt),
	}));
}

/** The conversation comments, oldest first; null when `general_comments.json` was not captured. */
export async function readGeneralComments(
	contextDir: string | undefined,
): Promise<GeneralComment[] | null> {
	const parsed = await readContextJson(contextDir, "general_comments.json");
	if (parsed === null) {
		return null;
	}
	const list = isJsonObject(parsed) ? objects(parsed.comments) : objects(parsed);
	return list.map((comment) => ({
		body: text(comment.body),
		author: optionalString(comment.author),
		bot: comment.bot === true,
		createdAt: optionalString(comment.createdAt ?? comment.created_at),
	}));
}

/** The threads and submitted decisions; null when `review_threads.json` was not captured. */
export async function readReviewThreads(
	contextDir: string | undefined,
): Promise<{ threads: ReviewThread[]; decisions: ReviewDecision[] } | null> {
	const parsed = await readContextJson(contextDir, "review_threads.json");
	if (parsed === null) {
		return null;
	}
	if (!isJsonObject(parsed)) {
		return { threads: [], decisions: [] };
	}
	return {
		threads: objects(parsed.threads).map((thread) => ({
			id: optionalNumber(thread.id),
			path: optionalString(thread.path),
			line: optionalNumber(thread.line),
			state: text(thread.state) || "UNRESOLVED",
			resolvedBy: optionalString(thread.resolvedBy),
			resolvedAt: optionalString(thread.resolvedAt),
			outdated: typeof thread.outdated === "boolean" ? thread.outdated : undefined,
			createdAt: optionalString(thread.createdAt),
		})),
		decisions: objects(parsed.reviewDecisions).map((decision) => ({
			state: text(decision.state),
			author: optionalString(decision.author),
			bot: decision.bot === true,
			submittedAt: optionalString(decision.submittedAt),
			body: optionalString(decision.body),
			dismissed: typeof decision.dismissed === "boolean" ? decision.dismissed : undefined,
		})),
	};
}

/** One task-list item of an issue or description body, as written. */
export interface CheckableItem {
	line: number;
	text: string;
	checked: boolean;
}

/** The task-list items (`- [ ]`, `* [x]`, `1. [ ]`) of a body, by line number. */
export function checkableItems(body: string): CheckableItem[] {
	const items: CheckableItem[] = [];
	for (const [index, line] of body.split(/\r?\n/u).entries()) {
		const match = /^\s*(?:[-*+]|\d+[.)])\s+\[(?<mark>[ xX])\]\s*(?<text>.*)$/u.exec(line);
		if (match?.groups) {
			items.push({
				line: index + 1,
				text: match.groups.text ?? "",
				checked: match.groups.mark !== " ",
			});
		}
	}
	return items;
}

/** Milliseconds between two ISO instants, or null when either is missing or unreadable. */
export function millisBetween(from: string | undefined, to: string | undefined): number | null {
	if (from === undefined || to === undefined) {
		return null;
	}
	const a = Date.parse(from);
	const b = Date.parse(to);
	return Number.isNaN(a) || Number.isNaN(b) ? null : b - a;
}

/** Whether instant `a` is strictly after instant `b`; false when either is missing. */
export function later(a: string | undefined, b: string | undefined): boolean {
	return a !== undefined && b !== undefined && Date.parse(a) > Date.parse(b);
}

/** A body on one line, cut to `length` characters, for a row's context or a flag. */
export function excerpt(body: string, length = 160): string {
	return body.replaceAll(/\s+/gu, " ").trim().slice(0, length);
}

/** Whether the change adds or removes a line within `radius` lines of the anchor. */
export function changeNear(
	diff: DiffFile | undefined,
	line: number | undefined,
	radius = 15,
): boolean {
	if (diff === undefined || line === undefined) {
		return false;
	}
	const near = (n: number) => Math.abs(n - line) <= radius;
	return [...diff.addedLines.keys()].some(near) || [...diff.removedLines.keys()].some(near);
}

/** A comment by someone other than the author, with what the record shows beside it. */
export interface ReviewerCommentRow {
	comment: ReviewComment;
	/** No earlier comment by others in the same thread: this one opens it. */
	opensThread: boolean;
	/** The author's later comments in the same thread. */
	replies: ReviewComment[];
	/** Others' later comments in the same thread. */
	followUps: ReviewComment[];
	thread?: ReviewThread;
}

/** Two comments of one thread: the record's `thread` id where both carry one, the same path and line otherwise. */
function sameThread(a: ReviewComment, b: ReviewComment): boolean {
	return a.thread !== undefined && b.thread !== undefined
		? a.thread === b.thread
		: a.path === b.path && a.line === b.line;
}

/**
 * One row per inline comment by someone other than the author, with the same thread's later
 * comments split by who wrote them. A thread is the record's `thread` id where both sides carry
 * one, and the same path and line otherwise.
 */
export function reviewerCommentRows(
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

/** The authored (non-merge) commits committed after an instant, and those touching a path. */
export function commitsAfter(
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

/** What the pull request metadata says about its merge, for the practices that judge one. */
export interface MergeFacts {
	merged: boolean;
	mergedAt?: string;
	mergedBy?: string;
	mergedByIsAuthor: boolean;
	author?: string;
}

export function mergeFacts(metadata: PullRequestMetadata): MergeFacts {
	const mergedAt = optionalString(metadata.merged_at);
	const mergedBy = optionalString(metadata.merged_by);
	const author = optionalString(metadata.author);
	return {
		merged: metadata.is_merged === true || mergedAt !== undefined || metadata.state === "MERGED",
		mergedAt,
		mergedBy,
		mergedByIsAuthor: mergedBy !== undefined && mergedBy === author,
		author,
	};
}

/** The merge as one record row: who merged, when, with the provider's own decision fields beside it. */
export function mergeRow(metadata: PullRequestMetadata, merge: MergeFacts): Hint {
	return {
		file: "inputs/context/metadata.json",
		line: 0,
		pattern: "merge",
		context: merge.merged
			? `merged${merge.mergedBy === undefined ? "" : ` by ${merge.mergedBy}`}${merge.mergedAt === undefined ? "" : ` at ${merge.mergedAt}`}`
			: "not merged",
		inDiff: false,
		flags: {
			merged: merge.merged,
			mergedBy: merge.mergedBy ?? "",
			mergedByIsAuthor: merge.mergedByIsAuthor,
			mergedAt: merge.mergedAt ?? "",
			author: merge.author ?? "",
			reviewDecision: metadata.review_decision ?? "",
			mergeStateStatus: metadata.merge_state_status ?? "",
			headChecks: metadata.head_checks ?? "",
		},
	};
}

/** One record row per submitted decision, oldest first, placed against the merge and the author. */
export function decisionRows(decisions: readonly ReviewDecision[], merge: MergeFacts): Hint[] {
	return decisions.map((d) => ({
		file: "inputs/context/review_threads.json",
		line: 0,
		pattern: "review decision",
		context: `${d.author ?? "?"}: ${d.state}${d.body === undefined ? "" : ` — ${excerpt(d.body, 120)}`}`,
		inDiff: false,
		flags: {
			state: d.state,
			author: d.author ?? "",
			submittedAt: d.submittedAt ?? "",
			beforeMerge: merge.mergedAt !== undefined && later(merge.mergedAt, d.submittedAt),
			isAuthor: d.author !== undefined && d.author === merge.author,
			dismissed: d.dismissed === true,
			bot: d.bot,
		},
	}));
}

/** Each reviewer's last decision, in the order the reviewers first decided. */
export function lastDecisionPerReviewer(decisions: readonly ReviewDecision[]): ReviewDecision[] {
	const last = new Map<string, ReviewDecision>();
	for (const d of decisions) {
		last.set(d.author ?? "", d);
	}
	return [...last.values()];
}

/**
 * One record row per thread the record does not mark RESOLVED, and one per thread resolved after
 * the merge when the record dates the resolution: a thread closed after the fact was open at the
 * merge, which is what the practice asks.
 */
export function unresolvedThreadRows(threads: readonly ReviewThread[], mergedAt?: string): Hint[] {
	const openAtMerge = (t: ReviewThread) =>
		t.state !== "RESOLVED" || (mergedAt !== undefined && later(t.resolvedAt, mergedAt));
	return threads.filter(openAtMerge).map((t) => ({
		file: t.path ?? "inputs/context/review_threads.json",
		line: t.line ?? 0,
		pattern: t.state === "RESOLVED" ? "thread resolved after the merge" : "unresolved thread",
		context: `${t.state}${t.path === undefined ? "" : ` on ${t.path}${t.line === undefined ? "" : `:${t.line}`}`}${t.resolvedAt === undefined ? "" : `, resolved ${t.resolvedAt}`}`,
		inDiff: false,
		flags: {
			id: t.id ?? 0,
			state: t.state,
			createdAt: t.createdAt ?? "",
			resolvedAt: t.resolvedAt ?? "",
			resolvedBy: t.resolvedBy ?? "",
			path: t.path ?? "",
			line: t.line ?? 0,
			outdated: t.outdated === true,
		},
	}));
}
