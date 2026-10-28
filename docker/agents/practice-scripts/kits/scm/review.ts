/**
 * Readers of the captured review records. A record that was not captured returns null, distinct from
 * a captured record with no items; a read that fails throws `UnratedError("unavailable")`.
 */

import { z } from "zod";

import { factsOf } from "../../lib/core/bounded-text.ts";
import type { PracticeInputs, ReviewLead } from "../../lib/core/contract.ts";
import { optionalString } from "../../lib/core/json.ts";
import { UnratedError, type Evidence } from "../../lib/core/practice-script.ts";
import type { ChangeCommit } from "./change.ts";
import type { SubIssueSource } from "./issue-classification.ts";
import { pullRequestChecksRecordSchema, type pullRequestRecordSchema } from "./records.ts";
import type { DiffFile } from "./types.ts";

export interface LinkedWorkItem {
	number: number;
	title: string;
	state?: string;
	/**
	 * `closesOnMerge` for a provider-recorded closing candidate, which may close on an eligible merge;
	 * `mentions` when only the text names it. Only `state` and `closedAt` say whether it closed.
	 */
	how?: "closesOnMerge" | "mentions";
	/** The provider's address of the issue; a closing candidate can belong to another repository. */
	url?: string;
	body: string;
	createdAt?: string;
	closedAt?: string;
	subIssuesTotal?: number;
	subIssuesCompleted?: number;
	/** What the counts are of; absent when the item carries no sub-issue counts. */
	subIssuesSource?: SubIssueSource;
}

export interface ReviewComment {
	id?: number;
	/** The `id` of the thread in `review_threads.json` this comment belongs to. */
	thread?: number;
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
	/** The line of `comments.json` that holds the comment's `body`; 0 when it is not known. */
	recordLine: number;
}

export interface GeneralComment {
	body: string;
	author?: string;
	bot: boolean;
	createdAt?: string;
	/** The line of `general_comments.json` that holds the comment's `body`; 0 when it is not known. */
	recordLine: number;
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
	/** When Hephaestus read a retained decision; not the provider's submission time. */
	observedAt?: string;
	body?: string;
	dismissed?: boolean;
}

const linkedWorkItemSchema = z.object({
	number: z.int().positive(),
	title: z.string().nullable(),
	body: z.string().nullable(),
	state: z.enum(["OPEN", "CLOSED"]).optional(),
	how: z.enum(["closesOnMerge", "mentions"]).optional(),
	url: z.string().nullable().optional(),
	createdAt: z.string().optional(),
	closedAt: z.string().optional(),
	subIssuesTotal: z.int().nonnegative().optional(),
	subIssuesCompleted: z.int().nonnegative().optional(),
	subIssuesSource: z.enum(["provider", "children"]).optional(),
});
const linkedWorkItemCaptureSchema = z.object({
	workItems: z.array(linkedWorkItemSchema),
	unresolvedReferences: z.array(z.int().positive()).optional(),
});
const commentSchema = z.object({
	body: z.string(),
	author: z.string().optional(),
	bot: z.boolean().optional(),
	createdAt: z.string().optional(),
	created_at: z.string().optional(),
});
const reviewCommentsSchema = z.array(
	commentSchema.extend({
		id: z.int().nonnegative().optional(),
		thread: z.int().nonnegative().optional(),
		in_reply_to: z.int().nonnegative().optional(),
		path: z.string(),
		line: z.int().nonnegative().optional(),
		side: z.enum(["LEFT", "RIGHT"]).optional(),
		outdated: z.boolean().optional(),
	}),
);
const generalCommentsSchema = z
	.object({ comments: z.array(commentSchema) })
	.transform((record) => record.comments);
const reviewThreadsSchema = z.object({
	review_decision: z.string().optional(),
	threads: z.array(
		z.object({
			id: z.int().nonnegative().optional(),
			path: z.string().optional(),
			line: z.int().nonnegative().optional(),
			state: z.enum(["UNRESOLVED", "RESOLVED"]),
			resolvedBy: z.string().optional(),
			resolvedAt: z.string().optional(),
			outdated: z.boolean().optional(),
			createdAt: z.string().optional(),
		}),
	),
	reviewDecisions: z.array(
		z.object({
			state: z.enum(["APPROVED", "CHANGES_REQUESTED", "COMMENTED", "DISMISSED"]),
			author: z.string().optional(),
			bot: z.boolean().optional(),
			submittedAt: z.string().optional(),
			observedAt: z.string().optional(),
			body: z.string().optional(),
			dismissed: z.boolean().optional(),
		}),
	),
});

async function readReviewRecord<T>(
	evidence: Pick<Evidence, "record">,
	name: string,
	schema: z.ZodType<T>,
): Promise<{ data: T; source: string } | null> {
	const record = await evidence.record(name);
	if (record.status === "ABSENT") {
		return null;
	}
	let value: unknown;
	try {
		value = JSON.parse(record.text);
	} catch {
		throw new UnratedError("unavailable", `${name} is not valid JSON`);
	}
	const parsed = schema.safeParse(value);
	if (!parsed.success) {
		const issue = parsed.error.issues[0];
		throw new UnratedError(
			"unavailable",
			`${name} has an invalid record shape at ${issue?.path.join(".") ?? ""}: ${issue?.message ?? "invalid"}`,
		);
	}
	return { data: parsed.data, source: record.text };
}

interface LinkedWorkItemCapture {
	items: LinkedWorkItem[];
	unresolved: number[];
}

/** The linked-work-item capture; null when not captured. */
export async function readLinkedWorkItemCapture(
	evidence: Pick<Evidence, "record">,
): Promise<LinkedWorkItemCapture | null> {
	const record = await readReviewRecord(
		evidence,
		"linked_work_items.json",
		linkedWorkItemCaptureSchema,
	);
	if (record === null) {
		return null;
	}
	return {
		items: record.data.workItems.map((item) => ({
			...item,
			title: item.title ?? "",
			body: item.body ?? "",
			url: item.url ?? undefined,
		})),
		unresolved: record.data.unresolvedReferences ?? [],
	};
}

/** The issues the change names, as `linked_work_items.json` records them; null when not captured. */
export async function readLinkedWorkItems(
	evidence: Pick<Evidence, "record">,
): Promise<LinkedWorkItem[] | null> {
	const capture = await readLinkedWorkItemCapture(evidence);
	return capture?.items ?? null;
}

// Server records place each key on its own line. Map body lines only when they match comments one to one.
async function readCommentRecord<T>(
	evidence: Pick<Evidence, "record">,
	name: string,
	schema: z.ZodType<T[]>,
): Promise<{ comments: T[]; bodyLines: number[] } | null> {
	const record = await readReviewRecord(evidence, name, schema);
	if (record === null) {
		return null;
	}
	const comments = record.data;
	const bodyLines = record.source
		.split("\n")
		.flatMap((line, index) => (/^\s*"body"\s*:/u.test(line) ? [index + 1] : []));
	return {
		comments,
		bodyLines: bodyLines.length === comments.length ? bodyLines : comments.map(() => 0),
	};
}

/** The inline comments, oldest first; null when `comments.json` was not captured. */
export async function readReviewComments(
	evidence: Pick<Evidence, "record">,
): Promise<ReviewComment[] | null> {
	const record = await readCommentRecord(evidence, "comments.json", reviewCommentsSchema);
	if (record === null) {
		return null;
	}
	return record.comments.map((comment, index) => ({
		id: comment.id,
		thread: comment.thread,
		inReplyTo: comment.in_reply_to,
		path: comment.path,
		line: comment.line,
		side: comment.side,
		outdated: comment.outdated === true,
		body: comment.body,
		author: comment.author,
		bot: comment.bot === true,
		createdAt: comment.created_at ?? comment.createdAt,
		recordLine: record.bodyLines[index] ?? 0,
	}));
}

/** The conversation comments, oldest first; null when `general_comments.json` was not captured. */
export async function readGeneralComments(
	evidence: Pick<Evidence, "record">,
): Promise<GeneralComment[] | null> {
	const record = await readCommentRecord(evidence, "general_comments.json", generalCommentsSchema);
	if (record === null) {
		return null;
	}
	return record.comments.map((comment, index) => ({
		body: comment.body,
		author: comment.author,
		bot: comment.bot === true,
		createdAt: comment.createdAt ?? comment.created_at,
		recordLine: record.bodyLines[index] ?? 0,
	}));
}

/** Null means review_threads.json was not captured; an empty array is a captured empty record. */
export async function readReviewThreads(evidence: Pick<Evidence, "record">): Promise<{
	threads: ReviewThread[];
	decisions: ReviewDecision[];
	reviewDecision?: string;
} | null> {
	const record = await readReviewRecord(evidence, "review_threads.json", reviewThreadsSchema);
	return record === null
		? null
		: {
				threads: record.data.threads,
				reviewDecision: record.data.review_decision,
				decisions: record.data.reviewDecisions.map((decision) => ({
					...decision,
					bot: decision.bot ?? false,
				})),
			};
}

/** Null means the checks source was not captured, never that CI was absent. */
export async function readPullRequestChecks(evidence: Pick<Evidence, "record">) {
	const record = await readReviewRecord(evidence, "checks.json", pullRequestChecksRecordSchema);
	return record?.data ?? null;
}

interface CheckableItem {
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

export function excerpt(body: string, length = 160): string {
	return body.replaceAll(/\s+/gu, " ").trim().slice(0, length);
}

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

interface MergeFacts {
	mergedAt?: string;
	mergedBy?: string;
	mergedByIsAuthor: boolean;
	author?: string;
}

type MergeRecord = Pick<
	z.infer<typeof pullRequestRecordSchema>,
	"merged_at" | "merged_by" | "author"
>;

/**
 * Whether a practice that only the merge moment brings into a review reads a merged pull request:
 * at that event, or when the review state that the review recorded is MERGED.
 */
export function reviewsMergedWork(review: PracticeInputs["review"]): boolean {
	return review.event?.name === "scm.pull_request.merged" || review.event?.state.state === "MERGED";
}

export function mergeFacts(record: MergeRecord): MergeFacts {
	const mergedAt = optionalString(record.merged_at);
	const mergedBy = optionalString(record.merged_by);
	const author = optionalString(record.author);
	return {
		mergedAt,
		mergedBy,
		mergedByIsAuthor: mergedBy !== undefined && mergedBy === author,
		author,
	};
}

/** Stored practice programs call this API with the work record before the merge facts. */
export function mergeLead(_record: MergeRecord, merge: MergeFacts, label: string): ReviewLead {
	return {
		at: { record: "metadata.json" },
		label,
		facts: factsOf({
			mergedBy: merge.mergedBy ?? "",
			mergedByIsAuthor: merge.mergedByIsAuthor,
			mergedAt: merge.mergedAt ?? "",
			author: merge.author ?? "",
		}),
	};
}

/** One lead per submitted decision, oldest first, placed against the merge and the author. */
export function decisionLeads(
	decisions: readonly ReviewDecision[],
	merge: MergeFacts,
	label: string,
): ReviewLead[] {
	return decisions.map((d) => ({
		at: { record: "review_threads.json" },
		label,
		facts: factsOf({
			state: d.state,
			author: d.author ?? "",
			submittedAt: d.submittedAt ?? "",
			...(merge.mergedAt !== undefined && d.submittedAt !== undefined
				? { beforeMerge: later(merge.mergedAt, d.submittedAt) }
				: {}),
			isAuthor: d.author !== undefined && d.author === merge.author,
			dismissed: d.dismissed === true,
			bot: d.bot,
		}),
	}));
}

/** Each reviewer's last decision, only where all their decision times are known. */
export function lastDecisionPerReviewer(decisions: readonly ReviewDecision[]): ReviewDecision[] {
	const last = new Map<string, ReviewDecision>();
	const unknown = new Set<string>();
	for (const d of decisions) {
		const reviewer = d.author ?? "";
		if (d.submittedAt === undefined) {
			unknown.add(reviewer);
		}
		last.set(reviewer, d);
	}
	return [...last.entries()]
		.filter(([reviewer]) => !unknown.has(reviewer))
		.map(([, decision]) => decision);
}

// Missing or invalid timestamps cannot establish event order.
export function dated(time: string | undefined): boolean {
	return !Number.isNaN(Date.parse(time ?? ""));
}

/** Whether the record dates a thread's resolution at or before a dated merge. */
export function resolvedByMerge(thread: ReviewThread, merge: MergeFacts): boolean {
	return (
		dated(thread.resolvedAt) && dated(merge.mergedAt) && !later(thread.resolvedAt, merge.mergedAt)
	);
}

/**
 * The threads to review against the merge: currently unresolved threads, and resolved threads the
 * record does not date as resolved by the merge — every one of them when the merge itself is
 * undated. A resolved thread opened after a dated merge was not open at it; opening times still
 * determine whether every other thread existed at the merge.
 */
export function candidateThreads(
	threads: readonly ReviewThread[],
	merge: MergeFacts,
): ReviewThread[] {
	return threads.filter(
		(t) =>
			t.state !== "RESOLVED" || (!resolvedByMerge(t, merge) && !later(t.createdAt, merge.mergedAt)),
	);
}

export type ThreadKind =
	| "unresolved-thread"
	| "thread-resolved-after-the-merge"
	| "resolved-thread-resolution-time-unknown"
	| "resolved-thread-merge-time-unknown"
	| "resolved-thread-both-times-unknown";

function threadKind(t: ReviewThread, merge: MergeFacts): ThreadKind {
	if (t.state !== "RESOLVED") {
		return "unresolved-thread";
	}
	if (dated(merge.mergedAt)) {
		return dated(t.resolvedAt)
			? "thread-resolved-after-the-merge"
			: "resolved-thread-resolution-time-unknown";
	}
	return dated(t.resolvedAt)
		? "resolved-thread-merge-time-unknown"
		: "resolved-thread-both-times-unknown";
}

export function threadLead(
	t: ReviewThread,
	merge: MergeFacts,
	labels: Readonly<Record<ThreadKind, string>>,
): ReviewLead {
	return {
		at: { record: "review_threads.json" },
		label: labels[threadKind(t, merge)],
		facts: factsOf({
			id: t.id ?? 0,
			state: t.state,
			createdAt: t.createdAt ?? "",
			resolvedAt: t.resolvedAt ?? "",
			resolvedBy: t.resolvedBy ?? "",
			path: t.path ?? "",
			line: t.line ?? 0,
			outdated: t.outdated === true,
		}),
	};
}
