import { describe, expect, it } from "vitest";

import type { ActivityPerson } from "@/api/types.gen";

import { competitionPositions } from "./people-positions";

const person = (id: number, contributions: number, reviews = 0): ActivityPerson => ({
	person: { id, login: `p${id}`, name: `P${id}`, avatarUrl: "", htmlUrl: "" },
	kind: "PERSON",
	counts: {
		contributions,
		pullRequestsOpened: 0,
		pullRequestsMerged: 0,
		pullRequestsReviewed: reviews,
		peopleHelped: 0,
		issuesOpened: 0,
		comments: 0,
		activeWeeks: 0,
	},
	weeks: [],
});

describe("competitionPositions", () => {
	it("gives a tie one position and skips the next (1, 2, 2, 4)", () => {
		const people = [person(1, 9), person(2, 5), person(3, 5), person(4, 1)];
		expect([...competitionPositions(people, "contributions")]).toStrictEqual([
			[1, 1],
			[2, 2],
			[3, 2],
			[4, 4],
		]);
	});

	it("positions by the sorted column's own count", () => {
		const people = [person(1, 9, 0), person(2, 1, 4)];
		expect(competitionPositions(people, "reviews").get(2)).toBe(1);
	});

	it("gives no positions in name order", () => {
		expect(competitionPositions([person(1, 9)], "name").size).toBe(0);
	});
});
