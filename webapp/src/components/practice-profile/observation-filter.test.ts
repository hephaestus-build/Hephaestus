import { assert, describe, expect, it } from "vitest";

import type { ResultList } from "@/api/types.gen";
import type { ReviewEntry } from "@/components/profile/reviews";
import { detailResult, detailReviews } from "@/stories/practice-detail-story-mock-data";

import {
	changedObservationFilter,
	filterObservations,
	hasObservationFilter,
	hasOwnObservationFilter,
	matchesObservation,
	observationLinkFilter,
	openOnArrival,
	shownObservations,
} from "./observation-filter";

const ids = (reviews: readonly ReviewEntry[]) =>
	reviews.flatMap((review) => review.results.map((result) => result.id));

const newest = detailReviews[0]?.results[0];
const older = detailReviews[1]?.results[0];
if (newest === undefined || older === undefined) {
	throw new Error("The fixture feed needs two reviews with results.");
}

describe("filterObservations", () => {
	it("keeps every observation without a filter", () => {
		expect(ids(filterObservations(detailReviews, {}))).toStrictEqual(ids(detailReviews));
	});

	it("matches the work's label, such as a pull request number", () => {
		expect(ids(filterObservations(detailReviews, { query: older.item.label }))).toStrictEqual([
			older.id,
		]);
	});

	it("matches the observation's words without case", () => {
		assert(older.summary !== null);
		const words = older.summary.slice(2, 20).toUpperCase();
		expect(ids(filterObservations(detailReviews, { query: words }))).toContain(older.id);
	});

	it("narrows by outcome", () => {
		const kept = filterObservations(detailReviews, { outcome: "MET" }).flatMap(
			(review) => review.results,
		);
		expect(kept.length).toBeGreaterThan(0);
		expect(kept.every((result) => result.outcome === "MET")).toBe(true);
	});

	it("drops a review left with no observation", () => {
		expect(filterObservations(detailReviews, { query: "nothing matches this" })).toStrictEqual([]);
	});
});

describe("a link to one observation", () => {
	it("sets the text to its work, narrows to that work by id and names it", () => {
		expect(observationLinkFilter(detailResult)).toStrictEqual({
			query: detailResult.item.label,
			observationId: detailResult.id,
			workId: detailResult.item.id,
		});
	});

	it("is let go of when the reader changes the text or the outcome", () => {
		const linked = observationLinkFilter(detailResult);
		expect(changedObservationFilter(linked, { query: "#1" })).toStrictEqual({
			query: "#1",
			observationId: undefined,
			workId: undefined,
		});
		const byOutcome = changedObservationFilter(linked, { outcome: "MET" });
		expect(byOutcome.observationId).toBeUndefined();
		// The work stays: the search field still names it.
		expect(byOutcome.workId).toBe(detailResult.item.id);
	});

	it("matches its work exactly, not every work whose label contains it", () => {
		const linked = observationLinkFilter(detailResult);
		const sameNumberPlusOne: ResultList = {
			...detailResult,
			id: "00000000-0000-4000-8000-00000000c731",
			item: { ...detailResult.item, id: "999999", label: `${detailResult.item.label}0` },
		};
		const sameLabelElsewhere: ResultList = {
			...detailResult,
			id: "00000000-0000-4000-8000-00000000c732",
			item: { ...detailResult.item, id: "999998", container: "another/repository" },
		};
		expect(matchesObservation(detailResult, linked)).toBe(true);
		expect(matchesObservation(sameNumberPlusOne, linked)).toBe(false);
		expect(matchesObservation(sameLabelElsewhere, linked)).toBe(false);
		// The reader's own text still searches as text.
		expect(matchesObservation(sameNumberPlusOne, { query: detailResult.item.label })).toBe(true);
	});

	it("does not count as a filter on its own", () => {
		expect(hasObservationFilter({ observationId: detailResult.id })).toBe(false);
		expect(hasObservationFilter({ query: "  " })).toBe(false);
	});
});

describe("hasOwnObservationFilter", () => {
	it("leaves the timeframe to its own control", () => {
		expect(hasOwnObservationFilter({ since: "7d" })).toBe(false);
		expect(hasObservationFilter({ since: "7d" })).toBe(true);
		expect(hasOwnObservationFilter({ since: "7d", query: "#15" })).toBe(true);
		expect(hasOwnObservationFilter({ outcome: "NOT_MET" })).toBe(true);
		expect(hasOwnObservationFilter({ workId: "work-1" })).toBe(true);
		expect(hasOwnObservationFilter({ query: "  " })).toBe(false);
	});
});

describe("shownObservations", () => {
	it("adds the asked observation in its place by time while its page is not loaded", () => {
		const loaded = detailReviews.slice(1);
		const shown = shownObservations(loaded, {}, newest);
		expect(ids(shown)[0]).toBe(newest.id);
		expect(ids(shown).filter((id) => id === newest.id)).toHaveLength(1);
	});

	it("keeps the asked observation out when the filter does not match it", () => {
		const shown = shownObservations(detailReviews.slice(1), { outcome: "MET" }, newest);
		expect(newest.outcome).not.toBe("MET");
		expect(ids(shown)).not.toContain(newest.id);
	});

	it("never shows the asked observation twice once its page is loaded", () => {
		const shown = shownObservations(detailReviews, {}, newest);
		expect(ids(shown).filter((id) => id === newest.id)).toHaveLength(1);
		expect(ids(shown)).toStrictEqual(ids(detailReviews));
	});
});

describe("openOnArrival", () => {
	it("opens the asked observation and no other, even before it arrives", () => {
		expect(openOnArrival(detailReviews, older.id)).toBe(older.id);
		expect(openOnArrival([], older.id)).toBe(older.id);
	});

	it("opens the newest shown observation otherwise", () => {
		expect(openOnArrival(detailReviews, undefined)).toBe(newest.id);
		expect(openOnArrival(detailReviews.slice(1), undefined)).toBe(older.id);
		expect(openOnArrival([], undefined)).toBeUndefined();
	});
});
