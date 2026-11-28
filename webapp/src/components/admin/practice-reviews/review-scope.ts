import type { KnownItemKind } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

export type ReviewTab = "overview" | "reviews" | "results" | "problems" | "feedback";

/** The time orders every list offers: when its rows were created or recorded, either way round. */
export const TIME_ORDERS = ["NEWEST", "OLDEST"] as const;

export type TimeOrder = (typeof TIME_ORDERS)[number];

export interface ReviewScope {
	groupSlug?: string[];
	practiceSlug?: string[];
	from?: string;
	to?: string;
	itemKind?: KnownItemKind;
	itemId?: string;
	reviewId?: string;
	resultId?: string;
	problemId?: string;
	feedbackId?: string;
	/** A tracked practice: every list narrows to it, whatever the practice filter holds. */
	selectedPractice?: string;
	/** The record above was picked with a row's crosshair, not filtered by (`review-selection.ts`). */
	selected?: true;
	/**
	 * The time order every list shares. It orders and does not narrow, so it is no scope facet: the
	 * tab's Reset keeps it, and only "Clear scope" puts it back to newest first.
	 */
	order?: TimeOrder;
}

export type ReviewScopeSearch = ReviewScope;

/**
 * A tab's search, as the scope reads it. A tab may widen `order` with an ordering of its own, such as
 * the results list's "Most actionable first"; the scope keeps only a time order.
 */
export type ScopeSource = Omit<ReviewScope, "order"> & { order?: string };

/** One part of the scope, as the toolbar shows it. */
export type ScopeFacet =
	| "group"
	| "practice"
	| "dates"
	| "kind"
	| "review"
	| "item"
	| "result"
	| "problem";

/** The tabs that list rows, and so offer the shared time order. */
export const ORDERED_TABS: readonly ReviewTab[] = ["reviews", "results", "problems", "feedback"];

/** The selection applies on every list: each one narrows to what follows from it. */
const SELECTION: readonly ScopeFacet[] = ["review", "item", "result"];

/**
 * Which parts of the scope each tab's endpoint can filter by. A part a tab cannot apply stays in its
 * URL, so the next tab gets it back, and the toolbar shows it as not applied.
 */
export const SCOPE_APPLIES: Record<ReviewTab, readonly ScopeFacet[]> = {
	overview: [],
	reviews: ["group", "practice", "dates", "review", "item"],
	results: ["group", "practice", "dates", "kind", ...SELECTION],
	problems: ["group", "practice", "dates", "kind", ...SELECTION, "problem"],
	feedback: ["group", "practice", "dates", "kind", "review", "item"],
};

function present<T>(values: T[] | undefined): T[] | undefined {
	return values !== undefined && values.length > 0 ? values : undefined;
}

function timeOrder(order: string | undefined): TimeOrder | undefined {
	return TIME_ORDERS.find((value) => value === order);
}

export function readScope(search: ScopeSource): ReviewScope {
	return {
		groupSlug: present(search.groupSlug),
		practiceSlug: present(search.practiceSlug),
		from: search.from,
		to: search.to,
		itemKind: search.itemKind,
		itemId: search.itemId,
		reviewId: search.reviewId,
		resultId: search.resultId,
		problemId: search.problemId,
		feedbackId: search.feedbackId,
		selectedPractice: search.selectedPractice,
		selected: search.selected,
		order: timeOrder(search.order),
	};
}

export function carriedScope(previous: ScopeSource): ReviewScope {
	// Only the keys with a value, so a link does not spell out every empty one.
	return Object.fromEntries(
		Object.entries(readScope(previous)).filter(([, value]) => value !== undefined),
	);
}

/**
 * Every part of the scope that narrows a list, cleared: what a list's "clear every filter" clears. The
 * time order stays, because it narrows nothing.
 */
export function clearedScopeFilters(): ReviewScope {
	return {
		groupSlug: undefined,
		practiceSlug: undefined,
		from: undefined,
		to: undefined,
		itemKind: undefined,
		itemId: undefined,
		reviewId: undefined,
		resultId: undefined,
		problemId: undefined,
		feedbackId: undefined,
		selectedPractice: undefined,
		selected: undefined,
	};
}

/** Every part of the scope, the time order included, cleared: what "Clear scope" clears. */
export function clearedScope(): ReviewScope {
	return { ...clearedScopeFilters(), order: undefined };
}

/** The parts of the scope that hold a value. */
export function setScopeFacets(scope: ReviewScope): ScopeFacet[] {
	const set: ScopeFacet[] = [];
	if ((scope.groupSlug?.length ?? 0) > 0) {
		set.push("group");
	}
	if ((scope.practiceSlug?.length ?? 0) > 0 || hasText(scope.selectedPractice)) {
		set.push("practice");
	}
	if (hasText(scope.from)) {
		set.push("dates");
	}
	if (scope.itemKind !== undefined) {
		set.push("kind");
	}
	if (hasText(scope.reviewId)) {
		set.push("review");
	}
	if (hasText(scope.itemId)) {
		set.push("item");
	}
	if (hasText(scope.resultId)) {
		set.push("result");
	}
	if (hasText(scope.problemId)) {
		set.push("problem");
	}
	return set;
}

/** True when a part of the scope that `tab` applies narrows its list. */
export function hasAppliedScope(tab: ReviewTab, scope: ReviewScope): boolean {
	return setScopeFacets(scope).some((facet) => SCOPE_APPLIES[tab].includes(facet));
}
