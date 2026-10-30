import { REVIEW_RANGES, type ReviewRange } from "@/components/admin/practice-reviews/review-range";

/**
 * The overview's range, remembered while the browser tab is open: the lists have dates of their own,
 * so a tab link does not carry the range, and the overview would otherwise open at its default each
 * time the reader came back to it. Session storage is per tab and per workspace; a browser that
 * refuses it, as a private window may, simply forgets.
 */
const KEY_PREFIX = "hephaestus.practice-reviews.range:";

function keyOf(workspaceSlug: string): string {
	return `${KEY_PREFIX}${workspaceSlug}`;
}

function isReviewRange(value: unknown): value is ReviewRange {
	return (REVIEW_RANGES as readonly unknown[]).includes(value);
}

/** The range last chosen on the overview in this tab, or undefined when there is none to read. */
export function rememberedRange(workspaceSlug: string): ReviewRange | undefined {
	try {
		const stored = sessionStorage.getItem(keyOf(workspaceSlug));
		return isReviewRange(stored) ? stored : undefined;
	} catch {
		return undefined;
	}
}

/** Keeps the overview's range for the rest of the tab's life. */
export function rememberRange(workspaceSlug: string, range: ReviewRange): void {
	try {
		sessionStorage.setItem(keyOf(workspaceSlug), range);
	} catch {
		// Storage refused: the overview opens at its default next time, as before.
	}
}

/**
 * The range the overview should switch to as it opens, or undefined to keep the one it has: the
 * remembered one when the address names none, since an address that names a range wins.
 */
export function rangeToRestore(
	workspaceSlug: string,
	searchStr: string,
	current: ReviewRange,
): ReviewRange | undefined {
	if (new URLSearchParams(searchStr).has("range")) {
		return undefined;
	}
	const remembered = rememberedRange(workspaceSlug);
	return remembered === current ? undefined : remembered;
}
