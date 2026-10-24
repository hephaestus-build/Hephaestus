import type { ProfileReview, ResultList } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import type { Outcome } from "@/components/practice-vocabulary/outcome-defs";
import { hasText } from "@/lib/text";

import type { ReviewTimeframe } from "./practice-profile-search";

/**
 * The filter over a practice's observations, on the practice level's Observations tab. Every part
 * lives in the route's search, so a link can set it and Back restores it.
 *
 * - `query`: free text, matched without case against the observation's words, its work (the label
 *   such as "#15", the title and the repository or channel) and the summaries of its problems.
 * - `outcome`: one outcome.
 * - `observationId`: the one observation a link asked for, such as a review's "Open observation".
 *   It narrows nothing by itself; the link sets `workId` and `query` to the observation's work
 *   beside it, so the tab shows that work's observations of this practice with the asked one among
 *   them.
 * - `workId`: the work a link narrowed to, by its id. While it is set it stands in for `query`, which
 *   then only names the work in the search field: "#15" is matched as that one work, never as text
 *   that "#150" or another repository's "#15" also contains.
 */
export interface ObservationFilter {
	query?: string;
	outcome?: Outcome;
	/** How far back the history reaches, read by the server; absent is every observation. */
	since?: ReviewTimeframe;
	observationId?: string;
	workId?: string;
}

export function hasObservationFilter(filter: ObservationFilter): boolean {
	return (
		hasText(filter.query?.trim()) ||
		filter.outcome !== undefined ||
		filter.since !== undefined ||
		hasText(filter.workId)
	);
}

/**
 * Whether the Observations tab's own filters narrow it: search, work or outcome. The timeframe is
 * the practice level's, with its own control in the tab row, so it is not one of them.
 */
export function hasOwnObservationFilter(filter: ObservationFilter): boolean {
	return hasText(filter.query?.trim()) || filter.outcome !== undefined || hasText(filter.workId);
}

/**
 * The filter a link to one observation sets: its work, by id and by its label for the search field,
 * and the observation itself.
 */
export function observationLinkFilter(
	result: Pick<ResultList, "id" | "item">,
): Required<Pick<ObservationFilter, "query" | "observationId" | "workId">> {
	return { query: result.item.label, observationId: result.id, workId: result.item.id };
}

/**
 * A change the reader makes to the text or the outcome. It lets go of the observation a link asked
 * for: the reader is now browsing, and an id they cannot see must not keep a row open or hold one
 * the filter no longer matches.
 */
export function changedObservationFilter(
	filter: ObservationFilter,
	change: Pick<ObservationFilter, "query" | "outcome" | "since">,
): ObservationFilter {
	// The search field showed the work's label; a change to it is the reader's own text, and the work
	// the link narrowed to lets go with it.
	return {
		...filter,
		...change,
		observationId: undefined,
		workId: "query" in change && change.query !== filter.query ? undefined : filter.workId,
	};
}

function textOf(result: ResultList): string {
	return [
		result.summary,
		result.item.label,
		result.item.title,
		result.item.container,
		...result.problems.map((problem) => problem.summary),
	]
		.filter(hasText)
		.join("\n")
		.toLowerCase();
}

export function matchesObservation(result: ResultList, filter: ObservationFilter): boolean {
	if (filter.outcome !== undefined && result.outcome !== filter.outcome) {
		return false;
	}
	if (hasText(filter.workId)) {
		return result.item.id === filter.workId;
	}
	const needle = filter.query?.trim().toLowerCase() ?? "";
	return needle === "" || textOf(result).includes(needle);
}

/** The reviews narrowed to the observations the filter keeps; a review left with none drops out. */
export function filterObservations(
	reviews: readonly ProfileReview[],
	filter: ObservationFilter,
): ProfileReview[] {
	if (!hasObservationFilter(filter)) {
		return [...reviews];
	}
	return reviews
		.map((review) => ({
			...review,
			results: review.results.filter((result) => matchesObservation(result, filter)),
		}))
		.filter((review) => review.results.length > 0);
}

export function carriesObservation(reviews: readonly ProfileReview[], id: string): boolean {
	return reviews.some((review) => review.results.some((result) => result.id === id));
}

/**
 * The observation a link asked for, read on its own: the feed loads reviews a page at a time, so
 * the asked one may sit on a page not loaded yet. `elsewhere` is an observation of another
 * practice, which this level never shows under its own name.
 */
export type SelectedObservationState = PanelState<{ result: ResultList }> | { status: "elsewhere" };

/**
 * The asked observation as a review entry of its own, for the history while its review's page is
 * not loaded. It carries what a history row shows: the work, the time and the one observation. The
 * time is when the observation was recorded, which is within its review.
 */
export function asReviewEntry(result: ResultList): ProfileReview {
	return {
		review: {
			id: result.reviewId,
			createdAt: result.recordedAt,
			completedAt: null,
			item: result.item,
			itemVersion: null,
			kind: "ITEM",
			origin: result.origin,
			status: "COMPLETE",
		},
		results: [result],
		feedback: [],
	};
}

/**
 * The history the tab shows: the loaded reviews the filter keeps and, while its page is not
 * loaded, the observation a link asked for, in its place by time. Never the asked observation twice.
 */
export function shownObservations(
	reviews: readonly ProfileReview[],
	filter: ObservationFilter,
	selected: ResultList | undefined,
): ProfileReview[] {
	const shown = filterObservations(reviews, filter);
	// The asked observation obeys the same filter as the loaded ones, so a page loading never
	// makes it appear or vanish.
	if (
		selected === undefined ||
		carriesObservation(reviews, selected.id) ||
		!matchesObservation(selected, filter)
	) {
		return shown;
	}
	const entry = asReviewEntry(selected);
	const at = entry.review.createdAt.getTime();
	const index = shown.findIndex((review) => review.review.createdAt.getTime() < at);
	return index === -1
		? [...shown, entry]
		: [...shown.slice(0, index), entry, ...shown.slice(index)];
}

/**
 * Which observation opens on arrival, the one rule for the tab, so it can be read in one place:
 *
 * 1. A link asked for one observation: that one opens, and no other, even while it still loads.
 * 2. Otherwise the newest shown observation opens, filtered or not, and no other: it is the one the
 *    reader came for, and the rest are its history.
 *
 * Every other row waits for a press. When the rule's target changes, as a filter or a link moves
 * it, the rows whose answer changed start again from the rule (`ReviewCard` keys each row by it).
 */
export function openOnArrival(
	shown: readonly ProfileReview[],
	observationId: string | undefined,
): string | undefined {
	if (observationId !== undefined) {
		return observationId;
	}
	return shown[0]?.results[0]?.id;
}
