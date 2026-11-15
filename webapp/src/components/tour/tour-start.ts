/**
 * What the page needs to start the tour, kept apart from the tour itself so the page's chunk carries
 * none of the tour.
 */

/** Marks "Take the tour", where focus goes after a first visit's tour closes. */
export const TOUR_START_PROPS = { "data-tour-start": "" } as const;
export const TOUR_START_SELECTOR = "[data-tour-start]";

/**
 * Each record of the tour names the account it belongs to, so a second person on a shared browser
 * never inherits a first one's offer or finished chapters. Records from before carry no account and
 * are never read: moving one into an account would credit it with chapters someone else finished.
 */
export const tourRecordKey = (record: "offered" | "progress", accountId: string) =>
	`hephaestus:guided-tour:practice-profile:${record}:${accountId}`;

/**
 * Whether this browser has offered the tour to this account on a first visit. Storage that cannot be
 * read counts as offered, as does an unknown account, so a browser that cannot remember never opens
 * the tour on every visit.
 */
export function tourOffered(accountId: string | undefined): boolean {
	if (accountId === undefined) {
		return true;
	}
	try {
		return window.localStorage.getItem(tourRecordKey("offered", accountId)) !== null;
	} catch {
		return true;
	}
}

/** Remembers the offer once it shows, so the tour never opens by itself again for this account here. */
export function rememberTourOffered(accountId: string | undefined): void {
	if (accountId === undefined) {
		return;
	}
	try {
		window.localStorage.setItem(tourRecordKey("offered", accountId), "1");
	} catch {
		// Unreachable storage already counts as offered.
	}
}
