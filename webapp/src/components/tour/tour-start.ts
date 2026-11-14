/**
 * What the page needs to start the tour, kept apart from the tour itself so the page's chunk carries
 * none of the tour.
 */

/** Marks "Take the tour", where focus goes after a first visit's tour closes. */
export const TOUR_START_PROPS = { "data-tour-start": "" } as const;
export const TOUR_START_SELECTOR = "[data-tour-start]";

const OFFERED_KEY = "hephaestus:guided-tour:practice-profile:offered";

/**
 * Whether this browser has offered the tour on a first visit. Storage that cannot be read counts as
 * offered, so a browser that cannot remember never opens the tour on every visit.
 */
export function tourOffered(): boolean {
	try {
		return window.localStorage.getItem(OFFERED_KEY) !== null;
	} catch {
		return true;
	}
}

/** Remembers the offer once it shows, so the tour never opens by itself again in this browser. */
export function rememberTourOffered(): void {
	try {
		window.localStorage.setItem(OFFERED_KEY, "1");
	} catch {
		// Unreachable storage already counts as offered.
	}
}
