/**
 * The smallest count of people a view shows, as words only. The server owns the rule
 * (`SmallCounts.MINIMUM`) and sends no smaller count: it sends null, or leaves the count out. The
 * webapp never applies the rule itself.
 */
export const SMALLEST_COUNT = 5;

/** What a withheld count reads as: "Fewer than 5". */
export const FEWER_THAN_SMALLEST = `Fewer than ${SMALLEST_COUNT}`;

/** Why a count can be missing, in the words of the user guide. */
export const SHOWN_FROM_SMALLEST = `Shown from ${SMALLEST_COUNT} people, to protect individuals`;
