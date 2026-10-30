import { z } from "zod";

import { hasText } from "@/lib/text";

import type { PracticeReviewLevel } from "./review-levels";
import type { ReviewSelection } from "./review-selection";

export type RecordKind = "review" | "result" | "problem" | "feedback" | "item" | "practice";

export interface RecordRef {
	kind: RecordKind;
	id: string;
}

const uuid = z.guid();
const itemId = z.string().regex(/^[1-9]\d{0,18}$/u);

function isRecordId(kind: RecordKind, id: string): boolean {
	switch (kind) {
		case "item": {
			return itemId.safeParse(id).success;
		}
		case "practice": {
			return hasText(id);
		}
		case "review":
		case "result":
		case "problem":
		case "feedback": {
			return uuid.safeParse(id).success;
		}
	}
}

const RECORD_KINDS: readonly RecordKind[] = [
	"review",
	"result",
	"problem",
	"feedback",
	"item",
	"practice",
];

function isRecordKind(kind: string): kind is RecordKind {
	return RECORD_KINDS.some((known) => known === kind);
}

/** The sheet of one record. */
export function recordLevel(record: RecordRef): PracticeReviewLevel {
	return { kind: record.kind, id: record.id };
}

/** The trace of one record. */
export function traceLevel(record: RecordRef): PracticeReviewLevel {
	return { kind: "trace", id: `${record.kind}:${record.id}` };
}

/** The record a level shows, or a trace stands on. Undefined for an id hand-edited into nonsense. */
export function levelRecord(level: PracticeReviewLevel): RecordRef | undefined {
	if (level.kind !== "trace") {
		return isRecordId(level.kind, level.id) ? { kind: level.kind, id: level.id } : undefined;
	}
	const separator = level.id.indexOf(":");
	const kind = level.id.slice(0, separator);
	const id = level.id.slice(separator + 1);
	return separator > 0 && isRecordKind(kind) && isRecordId(kind, id) ? { kind, id } : undefined;
}

export function recordSelection(
	record: RecordRef,
	context: { reviewId?: string; resultId?: string } = {},
): ReviewSelection | undefined {
	switch (record.kind) {
		case "review": {
			return { kind: "review", reviewId: record.id };
		}
		case "result": {
			return { kind: "result", resultId: record.id, reviewId: context.reviewId };
		}
		case "problem": {
			return context.resultId === undefined
				? undefined
				: { kind: "result", resultId: context.resultId, reviewId: context.reviewId };
		}
		case "feedback": {
			return { kind: "feedback", feedbackId: record.id, reviewId: context.reviewId };
		}
		case "practice": {
			return { kind: "practice", practiceSlug: record.id };
		}
		case "item": {
			return { kind: "item", itemId: record.id };
		}
	}
}

/** The record a selection tracks, which its trace stands on. */
export function selectionRecord(selection: ReviewSelection): RecordRef {
	switch (selection.kind) {
		case "review": {
			return { kind: "review", id: selection.reviewId };
		}
		case "result": {
			return { kind: "result", id: selection.resultId };
		}
		case "feedback": {
			return { kind: "feedback", id: selection.feedbackId };
		}
		case "practice": {
			return { kind: "practice", id: selection.practiceSlug };
		}
		case "item": {
			return { kind: "item", id: selection.itemId };
		}
	}
}
