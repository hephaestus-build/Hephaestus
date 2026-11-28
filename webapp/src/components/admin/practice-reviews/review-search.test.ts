import { describe, expect, it } from "vitest";

import { carriedScope, clearedScope, clearedScopeFilters } from "./review-scope";
import {
	feedbackQuery,
	feedbackSearchSchema,
	onePracticeQuery,
	problemsQuery,
	problemsSearchSchema,
	resultsQuery,
	resultsSearchSchema,
	reviewsQuery,
	reviewsSearchSchema,
} from "./review-search";

describe("practice review search", () => {
	it("canonicalizes invalid URL filters", () => {
		const feedback = feedbackSearchSchema.parse({
			state: ["DELIVERED", "made-up"],
			destination: ["ON_WORK", "IN_CHAT"],
			location: "made-up",
			from: "not-a-date",
			page: -4,
		});
		const results = resultsSearchSchema.parse({
			outcome: ["MET", "MET", "unknown"],
			severity: "MAJOR",
			from: "2026-08-10",
			to: "2026-08-01",
		});

		expect(feedback).toMatchObject({ state: ["DELIVERED"], destination: ["ON_WORK"] });
		expect(feedback.location).toBeUndefined();
		expect(feedback.from).toBeUndefined();
		// The lists have no pages, so a `page` in the URL is dropped.
		expect("page" in feedback).toBe(false);
		expect(results).toMatchObject({ outcome: ["MET"], severity: ["MAJOR"] });
		expect(results.to).toBeUndefined();
		expect(feedbackSearchSchema.parse({ to: "2026-07-03" }).to).toBeUndefined();
	});

	it("uses an inclusive day start and exclusive next-day end", () => {
		const search = feedbackSearchSchema.parse({ from: "2026-07-01", to: "2026-07-03" });
		const query = feedbackQuery(search, 25);

		expect(query.from).toBeInstanceOf(Date);
		expect(query.from?.getDate()).toBe(1);
		expect(query.from?.getHours()).toBe(0);
		expect(query.to).toBeInstanceOf(Date);
		expect(query.to?.getDate()).toBe(4);
		expect(query.to?.getHours()).toBe(0);
	});

	it("gives the reviews list the same day window as its sibling lists, alongside the status", () => {
		const search = reviewsSearchSchema.parse({
			status: ["COMPLETE", "FAILED"],
			from: "2026-07-01",
			to: "2026-07-03",
		});
		const query = reviewsQuery(search, 25);

		expect(query.from?.getDate()).toBe(1);
		expect(query.from?.getHours()).toBe(0);
		expect(query.to?.getDate()).toBe(4);
		expect(query.status).toStrictEqual(["COMPLETE", "FAILED"]);
	});

	it("drops a status a review cannot have", () => {
		expect(reviewsSearchSchema.parse({ status: ["ON_HOLD", "FAILED"] }).status).toStrictEqual([
			"FAILED",
		]);
	});

	it("sends the queue state a figure of the queue set, and drops one the queue does not count", () => {
		expect(reviewsQuery(reviewsSearchSchema.parse({ queueState: "HELD" }), 25).queueState).toBe(
			"HELD",
		);
		expect(reviewsSearchSchema.parse({ queueState: "QUEUED" }).queueState).toBeUndefined();
		expect(reviewsQuery(reviewsSearchSchema.parse({}), 25).queueState).toBeUndefined();
	});

	it("drops a reviews window that ends before it starts, rather than querying an empty range", () => {
		expect(reviewsSearchSchema.parse({ from: "2026-08-10", to: "2026-08-01" }).to).toBeUndefined();
		// A bare upper bound is not a window at all, and the endpoint would read it as "everything
		// before this day".
		expect(reviewsSearchSchema.parse({ to: "2026-08-01" }).to).toBeUndefined();
	});

	it("sends the chosen ordering as the parameter the endpoint names, and nothing when it is the default", () => {
		const chosen = resultsSearchSchema.parse({ order: "ACTIONABILITY" });
		const untouched = resultsSearchSchema.parse({});
		// The word another route already owns: a `sort=name` arriving from anywhere else is not an
		// ordering this list has, so it is dropped rather than sent on to the API.
		const foreign = resultsSearchSchema.parse({ order: "name" });

		expect(resultsQuery(chosen, 25).sort).toBe("ACTIONABILITY");
		expect(resultsQuery(untouched, 25).sort).toBeUndefined();
		expect(resultsQuery(foreign, 25).sort).toBeUndefined();
	});

	it("sends the shared time order to every list's endpoint", () => {
		const oldest = { order: "OLDEST" };

		expect(reviewsQuery(reviewsSearchSchema.parse(oldest), 25).sort).toBe("OLDEST");
		expect(resultsQuery(resultsSearchSchema.parse(oldest), 25).sort).toBe("OLDEST");
		expect(problemsQuery(problemsSearchSchema.parse(oldest), 25).sort).toBe("OLDEST");
		expect(feedbackQuery(feedbackSearchSchema.parse(oldest), 25).sort).toBe("OLDEST");
		// Only the results list has an ordering of its own; every other list drops it.
		expect(reviewsSearchSchema.parse({ order: "ACTIONABILITY" }).order).toBeUndefined();
		expect(feedbackSearchSchema.parse({ order: "ACTIONABILITY" }).order).toBeUndefined();
	});

	it("carries the time order to the next tab, but not the results list's own ordering", () => {
		expect(carriedScope({ from: "2026-09-01", order: "OLDEST" })).toStrictEqual({
			from: "2026-09-01",
			order: "OLDEST",
		});
		expect(carriedScope({ from: "2026-09-01", order: "ACTIONABILITY" })).toStrictEqual({
			from: "2026-09-01",
		});
	});

	it("clears the time order with the scope, but not with the filters", () => {
		expect(clearedScope()).toHaveProperty("order", undefined);
		expect(clearedScopeFilters()).not.toHaveProperty("order");
	});

	it("names the one practice a list narrows to, the tracked one first", () => {
		expect(onePracticeQuery({ practiceSlug: ["y"], selectedPractice: "x" })).toBe("x");
		expect(onePracticeQuery({ practiceSlug: ["y"] })).toBe("y");
		expect(onePracticeQuery({ practiceSlug: ["y", "z"] })).toBeUndefined();
		expect(onePracticeQuery({})).toBeUndefined();
	});

	it("sends a group only when the filter holds exactly one", () => {
		expect(resultsQuery(resultsSearchSchema.parse({ groupSlug: ["core"] }), 25).groupSlug).toBe(
			"core",
		);
		expect(
			resultsQuery(resultsSearchSchema.parse({ groupSlug: ["core", "docs"] }), 25).groupSlug,
		).toBeUndefined();
	});

	it("reads only a valid item id and UUID record ids", () => {
		const search = problemsSearchSchema.parse({
			itemId: "0",
			resultId: "not-a-uuid",
			problemId: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b70",
		});

		expect(search.itemId).toBeUndefined();
		expect(search.resultId).toBeUndefined();
		expect(search.problemId).toBe("0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b70");
	});

	it("narrows the problems list to one result and its item", () => {
		const resultId = "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b70";
		const query = problemsQuery(
			problemsSearchSchema.parse({ resultId, itemId: "42", severity: ["CRITICAL"] }),
			25,
		);

		expect(query).toMatchObject({ resultId, itemId: "42", severity: ["CRITICAL"], size: 25 });
	});

	it("reads the recorded problem ledger when filtering accepted or stale results", () => {
		for (const filter of [{ accepted: true }, { currentness: "STALE" }] as const) {
			const query = problemsQuery(problemsSearchSchema.parse(filter), 25);

			expect(query).toMatchObject({ ...filter, openOnly: false });
		}
		expect(problemsQuery(problemsSearchSchema.parse({}), 25).openOnly).toBe(false);
	});
});

describe("result outcome filter", () => {
	it("sends all four outcomes through one filter", () => {
		const outcome = ["MET", "NOT_MET", "DOES_NOT_APPLY", "COULD_NOT_DETERMINE"];
		expect(resultsQuery(resultsSearchSchema.parse({ outcome }), 25).outcome).toStrictEqual(outcome);
	});
});
