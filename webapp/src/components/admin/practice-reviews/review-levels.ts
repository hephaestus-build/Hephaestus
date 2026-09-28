import { z } from "zod";

import { ACTIVITY_RANGES } from "@/components/activity/activity-range";
import {
	type DetailStackEntry,
	detailStackSchema,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";
import type { KnownArtifactKind } from "@/lib/artifact-kinds";

import { reviewArtifactTypeFromSlug, reviewArtifactTypeSlug } from "./ReviewArtifact";

/**
 * The records Practice reviews opens over its tabs, as `?detail=kind:id`, by what each is called in
 * a level's header and in the path of the levels over it. They link to each other — feedback to its
 * observations and back — so every tab hosts every kind.
 */
const LEVEL_KINDS = ["review", "observation", "feedback", "work", "practice"] as const;

export type PracticeReviewLevelKind = (typeof LEVEL_KINDS)[number];
export type PracticeReviewLevel = DetailStackEntry<PracticeReviewLevelKind>;

export const PRACTICE_REVIEW_LEVEL_LABELS = {
	review: "Review",
	observation: "Observation",
	feedback: "Feedback",
	work: "Reviewed work",
	practice: "Practice",
} as const satisfies Record<PracticeReviewLevelKind, string>;

/**
 * How far back the overview and a practice level count. Longer than Activity's week: reviews run
 * per piece of work, so a week of them is often too few to read a mix from.
 */
const rangeFilterSchema = z.object({
	range: z.enum(ACTIVITY_RANGES).default("30d").catch("30d"),
});

export const PRACTICE_REVIEWS_SEARCH_DEFAULTS = rangeFilterSchema.parse({});

export const practiceReviewsSearchSchema = rangeFilterSchema.extend(
	detailStackSchema(LEVEL_KINDS).shape,
);

export type PracticeReviewsSearch = z.infer<typeof practiceReviewsSearchSchema>;

export function parsePracticeReviewLevels(raw: string[] | undefined): PracticeReviewLevel[] {
	return parseDetailStack(raw, LEVEL_KINDS);
}

export const reviewLevel = (jobId: string): PracticeReviewLevel => ({ kind: "review", id: jobId });

export const observationLevel = (observationId: string): PracticeReviewLevel => ({
	kind: "observation",
	id: observationId,
});

export const feedbackLevel = (feedbackId: string): PracticeReviewLevel => ({
	kind: "feedback",
	id: feedbackId,
});

export const practiceLevel = (practiceSlug: string): PracticeReviewLevel => ({
	kind: "practice",
	id: practiceSlug,
});

/**
 * `work:pull-request:42`. Undefined for a kind this build cannot name in a URL: the work still
 * renders, it just cannot open a level of its own.
 */
export function workLevel(
	artifactKind: string,
	artifactId: number | string,
): PracticeReviewLevel | undefined {
	const slug = reviewArtifactTypeSlug(artifactKind);
	return slug === undefined ? undefined : { kind: "work", id: `${slug}:${artifactId}` };
}

/** A `work` level's id read back; undefined for one hand-edited into something that is not work. */
export function parseWorkLevel(
	id: string,
): { artifactKind: KnownArtifactKind; artifactId: number } | undefined {
	const separator = id.lastIndexOf(":");
	const artifactKind = reviewArtifactTypeFromSlug(id.slice(0, separator));
	const artifactId = Number(id.slice(separator + 1));
	return artifactKind !== undefined && Number.isSafeInteger(artifactId) && artifactId > 0
		? { artifactKind, artifactId }
		: undefined;
}
