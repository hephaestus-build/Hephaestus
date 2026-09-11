import { describe, expect, it } from "vitest";

import {
	feedbackQuery,
	feedbackSearchSchema,
	observationsQuery,
	observationsSearchSchema,
	runsQuery,
	runsSearchSchema,
} from "./review-search";

describe("practice review search", () => {
	it("canonicalizes invalid URL filters", () => {
		const feedback = feedbackSearchSchema.parse({
			deliveryState: ["DELIVERED", "made-up"],
			channel: ["IN_APP", "IN_CHAT", "made-up"],
			from: "not-a-date",
			page: -4,
		});
		const findings = observationsSearchSchema.parse({
			assessment: ["GOOD", "GOOD", "unknown"],
			severity: "MAJOR",
			from: "2026-08-10",
			to: "2026-08-01",
		});

		expect(feedback).toMatchObject({ deliveryState: ["DELIVERED"] });
		// IN_APP survives now that the in-app lane has a producer and the toolbar offers the place:
		// a filter the toolbar cannot show would be applied with nothing on screen saying so and no way
		// to clear it short of a full reset, which is why `made-up` still goes.
		expect(feedback.channel).toStrictEqual(["IN_APP", "IN_CHAT"]);
		expect(feedback.from).toBeUndefined();
		expect(feedback.page).toBeUndefined();
		expect(findings).toMatchObject({ assessment: ["GOOD"], severity: ["MAJOR"] });
		expect(findings.to).toBeUndefined();
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
		const search = runsSearchSchema.parse({
			status: "COMPLETED",
			from: "2026-07-01",
			to: "2026-07-03",
		});
		const query = runsQuery(search, 25);

		// The same half-open rule the other two lists use, so a date pasted between the three screens
		// selects the same days on each.
		expect(query.from?.getDate()).toBe(1);
		expect(query.from?.getHours()).toBe(0);
		expect(query.to?.getDate()).toBe(4);
		// Both filters travel: a status must not be dropped once a range is picked, which is the
		// composition the endpoint applies and the list promises.
		expect(query.status).toBe("COMPLETED");
	});

	it("drops a reviews window that ends before it starts, rather than querying an empty range", () => {
		expect(runsSearchSchema.parse({ from: "2026-08-10", to: "2026-08-01" }).to).toBeUndefined();
		// A bare upper bound is not a window at all, and the endpoint would read it as "everything
		// before this day".
		expect(runsSearchSchema.parse({ to: "2026-08-01" }).to).toBeUndefined();
	});

	it("sends the chosen ordering as the parameter the endpoint names, and nothing when it is the default", () => {
		const chosen = observationsSearchSchema.parse({ order: "ACTIONABILITY" });
		const untouched = observationsSearchSchema.parse({});
		// The word another route already owns: a `sort=name` arriving from anywhere else is not an
		// ordering this list has, so it is dropped rather than sent on to the API.
		const foreign = observationsSearchSchema.parse({ order: "name" });

		expect(observationsQuery(chosen, 25).sort).toBe("ACTIONABILITY");
		expect(observationsQuery(untouched, 25).sort).toBeUndefined();
		expect(observationsQuery(foreign, 25).sort).toBeUndefined();
	});

	it("does not send an artifact id without its type", () => {
		const query = feedbackQuery(feedbackSearchSchema.parse({ artifactId: "42" }), 25);
		expect(query.artifactId).toBeUndefined();
	});
});

describe("observation assessment status filter", () => {
	it("sends status independently of presence and assessment", () => {
		const unassessed = observationsSearchSchema.parse({
			assessmentStatus: ["NOT_APPLICABLE", "UNDETERMINED"],
		});
		expect(observationsQuery(unassessed, 25)).toMatchObject({
			assessmentStatus: ["NOT_APPLICABLE", "UNDETERMINED"],
			presence: undefined,
			assessment: undefined,
		});
		const assessed = observationsSearchSchema.parse({
			assessmentStatus: ["ASSESSED"],
			presence: ["ABSENT"],
			assessment: ["GOOD"],
		});
		expect(observationsQuery(assessed, 25)).toMatchObject({
			assessmentStatus: ["ASSESSED"],
			presence: ["ABSENT"],
			assessment: ["GOOD"],
		});
	});
});
