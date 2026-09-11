import { z } from "zod";

import { ASSESSMENT_DEFS } from "@/components/practice-vocabulary/assessment-defs";
import { ASSESSMENT_STATUS_DEFS } from "@/components/practice-vocabulary/assessment-status-defs";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-outcome-defs";
import { FILTERABLE_PLACES } from "@/components/practice-vocabulary/delivery-place-defs";
import { PRESENCE_DEFS } from "@/components/practice-vocabulary/presence-defs";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { statusValues } from "@/components/practice-vocabulary/status-def";
import {
	reasonsInFamilies,
	WITHHOLDING_FAMILY_DEFS,
} from "@/components/practice-vocabulary/withholding-defs";
import { ARTIFACT_KIND_VALUES, type KnownArtifactKind } from "@/lib/artifact-kinds";
import { dayAfterInstant, dayStartInstant, fromDayParam } from "@/lib/date-range-search";
import { multiValue, narrowToEnum } from "@/lib/search-params";

/**
 * Read by the query and by the skeleton that stands in for the results, so a skeleton cannot draw a
 * different number of rows than the page it replaces and shift the pagination when results arrive.
 */
export const REVIEW_PAGE_SIZE = 25;

/**
 * How often a queued or running review is re-asked for, on every screen that watches one. Applied
 * through TanStack Query's `refetchInterval`, which stops on its own at a terminal status.
 */
export const ACTIVE_REVIEW_POLL_MS = 5_000;

/**
 * Ordering names the server understands. `ACTIONABILITY` puts shortfalls first, worst severity down
 * to informational, then strengths, then the observations that judged nothing.
 */
export const OBSERVATION_SORTS = ["NEWEST", "ACTIONABILITY"] as const;
export type ObservationSort = (typeof OBSERVATION_SORTS)[number];

const uuidParam = z.uuid().optional().catch(undefined);
const positiveId = z.coerce.number().int().positive().optional().catch(undefined);
const page = z.coerce.number().int().min(0).optional().catch(undefined);
const day = z.iso.date().optional().catch(undefined);
/**
 * Every allowlist below is a status registry's own key set, so a URL filter and the dropdown that
 * offers it cannot come apart.
 *
 * <p>A parser must not admit a value the control it feeds has no way to display or clear: `channel`
 * narrows to `FILTERABLE_PLACES` rather than the whole wire union, because a hand-typed place the
 * toolbar cannot offer would apply an invisible filter that the next tick of any place then discards
 * without saying so — a facet emits only the options it was given.
 */
const enumValues = <T extends string>(allowed: readonly T[]) =>
	multiValue.transform((values): T[] | undefined => narrowToEnum(values, allowed));

const scope = {
	agentJobId: uuidParam,
	artifactKind: z.enum(ARTIFACT_KIND_VALUES).optional().catch(undefined),
	artifactId: positiveId,
	from: day,
	to: day,
};

function canonicalDateRange<T extends { from?: string; to?: string }>(search: T): T {
	if (!search.from || (search.to && search.to < search.from)) {
		return { ...search, to: undefined };
	}
	return search;
}

export const feedbackSearchSchema = z
	.object({
		...scope,
		page,
		deliveryState: enumValues(statusValues(DELIVERY_STATE_DEFS)),
		// The URL carries families, not individual reasons: the family is the question an operator
		// asks, and `feedbackQuery` expands it to the reasons the API filters on.
		withheldFamily: enumValues(statusValues(WITHHOLDING_FAMILY_DEFS)),
		channel: enumValues(FILTERABLE_PLACES),
		recipientUserId: positiveId,
	})
	.transform(canonicalDateRange);

export const observationsSearchSchema = z
	.object({
		...scope,
		page,
		groupSlug: multiValue,
		practiceSlug: multiValue,
		assessmentStatus: enumValues(statusValues(ASSESSMENT_STATUS_DEFS)).optional(),
		presence: enumValues(statusValues(PRESENCE_DEFS)),
		assessment: enumValues(statusValues(ASSESSMENT_DEFS)),
		severity: enumValues(statusValues(SEVERITY_DEFS)),
		subjectUserId: positiveId,
		// Spelled `order`, not `sort`, which is what the endpoint calls it: other routes already put a
		// `sort` in the URL with entirely different values, and TanStack's search params are one
		// namespace — another meaning of the word makes `search={(previous) => previous}`, the idiom
		// every link on this screen uses to carry the reader's filters forward, stop compiling.
		order: z.enum(OBSERVATION_SORTS).optional().catch(undefined),
	})
	.transform(canonicalDateRange);

/**
 * `from`/`to` window when a review was *requested*. Only those two are borrowed from `scope`, not
 * the whole object: a review *is* the job, so `agentJobId` would be a self-reference, and the
 * artifact pair belongs to the lists of what a review produced rather than to the list of reviews.
 */
export const runsSearchSchema = z
	.object({
		page,
		status: z.enum(statusValues(REVIEW_STATUS_DEFS)).optional().catch(undefined),
		from: day,
		to: day,
	})
	.transform(canonicalDateRange);

export type FeedbackSearch = z.infer<typeof feedbackSearchSchema>;
export type ObservationsSearch = z.infer<typeof observationsSearchSchema>;
export type RunsSearch = z.infer<typeof runsSearchSchema>;

export type ReviewScopeSearch = {
	agentJobId?: string;
	artifactKind?: KnownArtifactKind;
	artifactId?: number;
	from?: string;
	to?: string;
};

export function reviewScopeSearch(search: ReviewScopeSearch): ReviewScopeSearch {
	return {
		agentJobId: search.agentJobId,
		artifactKind: search.artifactKind,
		artifactId: search.artifactKind ? search.artifactId : undefined,
		from: search.from,
		to: search.to,
	};
}

/**
 * A picked pair of days becomes the half-open instant window the API takes: midnight on `from`, and
 * midnight on the day *after* `to`, so the day the reader picked last is included whole.
 */
function dateWindowQuery(search: { from?: string; to?: string }) {
	const from = fromDayParam(search.from);
	const to = fromDayParam(search.to);
	return {
		from: from ? dayStartInstant(from) : undefined,
		to: to ? dayAfterInstant(to) : undefined,
	};
}

function scopeQuery(search: ReviewScopeSearch) {
	return {
		agentJobId: search.agentJobId,
		artifactKind: search.artifactKind,
		artifactId: search.artifactKind ? search.artifactId : undefined,
		...dateWindowQuery(search),
	};
}

export function runsQuery(search: RunsSearch, size: number) {
	return {
		...dateWindowQuery(search),
		page: search.page ?? 0,
		size,
		status: search.status,
	};
}

export function feedbackQuery(search: FeedbackSearch, size: number) {
	return {
		...scopeQuery(search),
		page: search.page ?? 0,
		size,
		deliveryState: search.deliveryState,
		suppressionReason: search.withheldFamily?.length
			? reasonsInFamilies(search.withheldFamily)
			: undefined,
		channel: search.channel,
		recipientUserId: search.recipientUserId,
	};
}

export function observationsQuery(search: ObservationsSearch, size: number) {
	return {
		...scopeQuery(search),
		page: search.page ?? 0,
		size,
		groupSlug: search.groupSlug?.length ? search.groupSlug : undefined,
		practiceSlug: search.practiceSlug?.length ? search.practiceSlug : undefined,
		assessmentStatus: search.assessmentStatus,
		presence: search.presence,
		assessment: search.assessment,
		severity: search.severity,
		subjectUserId: search.subjectUserId,
		sort: search.order,
	};
}
