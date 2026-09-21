/**
 * Read-only helpers for the review record the server projects beside a change: the linked issues,
 * the inline and general comments, the review threads and decisions. Each reader returns what the
 * file carries in the shape the server wrote it, best effort, and an empty list when the file is
 * absent or not what it says.
 */

import { readContextJson } from "./context.ts";
import { isJsonObject } from "./practice-contract.ts";

export interface LinkedWorkItem {
	number: number;
	title: string;
	state?: string;
	body: string;
	createdAt?: string;
	closedAt?: string;
	subIssuesTotal?: number;
	subIssuesCompleted?: number;
}

/** A comment anchored to a line of the change (`comments.json`). */
export interface ReviewComment {
	path: string;
	line?: number;
	body: string;
	author?: string;
	createdAt?: string;
}

/** A comment in the conversation, not on a line (`general_comments.json`). */
export interface GeneralComment {
	body: string;
	author?: string;
	createdAt?: string;
}

export interface ReviewThread {
	path?: string;
	line?: number;
	state: string;
	resolvedBy?: string;
	outdated?: boolean;
}

export interface ReviewDecision {
	state: string;
	author?: string;
	submittedAt?: string;
	dismissed?: boolean;
}

function text(value: unknown): string {
	return typeof value === "string" ? value : "";
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

/** The issues the change names, as `linked_work_items.json` records them. */
export async function readLinkedWorkItems(
	contextDir: string | undefined,
): Promise<LinkedWorkItem[]> {
	const parsed = await readContextJson(contextDir, "linked_work_items.json");
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
				body: text(item.body ?? item.description),
				createdAt: optionalString(item.createdAt),
				closedAt: optionalString(item.closedAt),
				subIssuesTotal: optionalNumber(item.subIssuesTotal),
				subIssuesCompleted: optionalNumber(item.subIssuesCompleted),
			},
		];
	});
}

export async function readReviewComments(contextDir: string | undefined): Promise<ReviewComment[]> {
	const parsed = await readContextJson(contextDir, "comments.json");
	const list = isJsonObject(parsed) ? objects(parsed.comments) : objects(parsed);
	return list.map((comment) => ({
		path: text(comment.path),
		line: optionalNumber(comment.line),
		body: text(comment.body),
		author: optionalString(comment.author),
		createdAt: optionalString(comment.created_at ?? comment.createdAt),
	}));
}

export async function readGeneralComments(
	contextDir: string | undefined,
): Promise<GeneralComment[]> {
	const parsed = await readContextJson(contextDir, "general_comments.json");
	const list = isJsonObject(parsed) ? objects(parsed.comments) : objects(parsed);
	return list.map((comment) => ({
		body: text(comment.body),
		author: optionalString(comment.author),
		createdAt: optionalString(comment.createdAt ?? comment.created_at),
	}));
}

export async function readReviewThreads(
	contextDir: string | undefined,
): Promise<{ threads: ReviewThread[]; decisions: ReviewDecision[] }> {
	const parsed = await readContextJson(contextDir, "review_threads.json");
	if (!isJsonObject(parsed)) {
		return { threads: [], decisions: [] };
	}
	return {
		threads: objects(parsed.threads).map((thread) => ({
			path: optionalString(thread.path),
			line: optionalNumber(thread.line),
			state: text(thread.state) || "UNRESOLVED",
			resolvedBy: optionalString(thread.resolvedBy),
			outdated: typeof thread.outdated === "boolean" ? thread.outdated : undefined,
		})),
		decisions: objects(parsed.reviewDecisions).map((decision) => ({
			state: text(decision.state),
			author: optionalString(decision.author),
			submittedAt: optionalString(decision.submittedAt),
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
