import { z } from "zod";

import { REVIEW_RANGES, reviewRangeOf } from "@/components/admin/practice-reviews/review-range";
import {
	type DetailStackEntry,
	detailStackSchema,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";
import { workspaceAdminHead } from "@/lib/page-title";

const LEVEL_KINDS = [
	"review",
	"result",
	"problem",
	"feedback",
	"item",
	"practice",
	"trace",
] as const;

export type PracticeReviewLevelKind = (typeof LEVEL_KINDS)[number];
export type PracticeReviewLevel = DetailStackEntry<PracticeReviewLevelKind>;

export const PRACTICE_REVIEW_LEVEL_LABELS = {
	review: "Review",
	result: "Observation",
	problem: "Problem",
	feedback: "Feedback",
	item: "Work",
	practice: "Practice",
	trace: "Trace",
} as const satisfies Record<PracticeReviewLevelKind, string>;

/**
 * How far back the overview counts. Longer than Activity's week: reviews run
 * per piece of work, so a week of them is often too few to read a mix from.
 */
const rangeFilterSchema = z.object({
	// An address from before that asked for 12 months opens all time, which took its place.
	range: z.preprocess(reviewRangeOf, z.enum(REVIEW_RANGES).default("30d").catch("30d")),
});

export const PRACTICE_REVIEWS_SEARCH_DEFAULTS = rangeFilterSchema.parse({});

export const practiceReviewsSearchSchema = rangeFilterSchema.extend({
	...detailStackSchema(LEVEL_KINDS).shape,
	queue: z.literal("approvals").optional().catch(undefined),
});

export type PracticeReviewsSearch = z.infer<typeof practiceReviewsSearchSchema>;

export function practiceReviewsHead(tab: string) {
	return ({ match }: { match: { search: { detail?: string[] } } }) => {
		const front = parsePracticeReviewLevels(match.search.detail).at(-1);
		return workspaceAdminHead(
			front === undefined ? tab : `${PRACTICE_REVIEW_LEVEL_LABELS[front.kind]} · Practice reviews`,
		)();
	};
}

export function parsePracticeReviewLevels(raw: string[] | undefined): PracticeReviewLevel[] {
	return parseDetailStack(raw, LEVEL_KINDS);
}

export const reviewLevel = (reviewId: string): PracticeReviewLevel => ({
	kind: "review",
	id: reviewId,
});

export const resultLevel = (resultId: string): PracticeReviewLevel => ({
	kind: "result",
	id: resultId,
});

export const problemLevel = (problemId: string): PracticeReviewLevel => ({
	kind: "problem",
	id: problemId,
});

export const feedbackLevel = (feedbackId: string): PracticeReviewLevel => ({
	kind: "feedback",
	id: feedbackId,
});

export const practiceLevel = (practiceSlug: string): PracticeReviewLevel => ({
	kind: "practice",
	id: practiceSlug,
});

export const itemLevel = (itemId: string): PracticeReviewLevel => ({ kind: "item", id: itemId });
