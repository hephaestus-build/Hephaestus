/**
 * What a page needs to start the tour, kept apart from the tour itself so the page's chunk carries
 * none of the tour.
 */

/** Marks "Take the tour", where focus goes when the tour closes and what started it is gone. */
export const TOUR_START_PROPS = { "data-tour-start": "" } as const;
export const TOUR_START_SELECTOR = "[data-tour-start]";

/** The tour's chapters, in their order. The Practice profile's `tour` search param names one. */
export const TOUR_CHAPTER_IDS = [
	"how-it-works",
	"your-profile",
	"going-deeper",
	"across-the-workspace",
] as const;

/**
 * Each record of the tour names the account it belongs to, so a second person on a shared browser
 * never inherits a first one's finished chapters. Records from before carry no account and are never
 * read: moving one into an account would credit it with chapters someone else finished.
 */
export const tourRecordKey = (record: "progress", accountId: string) =>
	`hephaestus:guided-tour:practice-profile:${record}:${accountId}`;
