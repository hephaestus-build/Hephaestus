/**
 * Readers of the captured review records. A record that was not captured returns null, distinct from
 * a captured record with no items; a read that fails throws `UnratedError("unavailable")`.
 */

import { z } from "zod";

import { captureFailure, type Evidence } from "../../lib/core/practice-script.ts";
import type { SubIssueSource } from "./issue-classification.ts";
import { pullRequestChecksRecordSchema } from "./records.ts";

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
	/** The text may be a Hephaestus copy or a quote; its authorship is not established. */
	quotesHephaestusMarker?: boolean;
	createdAt?: string;
	/** The line of `comments.json` that holds the comment's `body`; 0 when it is not known. */
	recordLine: number;
}

export interface GeneralComment {
	body: string;
	author?: string;
	bot: boolean;
	quotesHephaestusMarker?: boolean;
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
	quotesHephaestusMarker?: boolean;
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
	quotesHephaestusMarker: z.boolean().optional(),
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
	.object({ comments: z.array(commentSchema), truncated: z.boolean().optional() })
	.transform((record) => {
		wholeRecord(record, "general_comments.json");
		return record.comments;
	});
const reviewThreadsSchema = z.object({
	truncated: z.boolean().optional(),
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
			quotesHephaestusMarker: z.boolean().optional(),
			submittedAt: z.string().optional(),
			observedAt: z.string().optional(),
			body: z.string().optional(),
			dismissed: z.boolean().optional(),
		}),
	),
});

/** A record the provider read did not finish cannot show that something is absent, so no script reads it as if it could. */
function wholeRecord(record: { truncated?: boolean }, name: string): void {
	if (record.truncated === true) {
		throw captureFailure("unavailable", `${name} does not hold the whole record`);
	}
}

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
		throw captureFailure("unavailable", `${name} is not valid JSON`);
	}
	const parsed = schema.safeParse(value);
	if (!parsed.success) {
		const issue = parsed.error.issues[0];
		throw captureFailure(
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
	// comments.json is a bare list. The thread record of the same read says whether that read finished.
	const threads = await readReviewRecord(
		evidence,
		"review_threads.json",
		z.object({ truncated: z.boolean().optional() }),
	);
	if (threads !== null) {
		wholeRecord(threads.data, "comments.json");
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
		...(comment.quotesHephaestusMarker === undefined
			? {}
			: { quotesHephaestusMarker: comment.quotesHephaestusMarker }),
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
		...(comment.quotesHephaestusMarker === undefined
			? {}
			: { quotesHephaestusMarker: comment.quotesHephaestusMarker }),
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
	if (record !== null) {
		wholeRecord(record.data, "review_threads.json");
	}
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

/** Whether instant `a` is strictly after instant `b`; false when either is missing. */
export function later(a: string | undefined, b: string | undefined): boolean {
	return a !== undefined && b !== undefined && Date.parse(a) > Date.parse(b);
}

// Missing or invalid timestamps cannot establish event order.
export function dated(time: string | undefined): boolean {
	return !Number.isNaN(Date.parse(time ?? ""));
}
