import { describe, expect, it } from "vitest";

import type { ReviewRef } from "@/api/types.gen";

import { tracedItem } from "./fixtures";
import { groupByDay, reviewOrdinalLabel } from "./review-groups";

const NOW = new Date(2026, 8, 22, 21, 30);

const review = (id: string, createdAt: Date, itemId = "1423"): ReviewRef => ({
	id,
	kind: "ITEM",
	item: tracedItem(itemId),
	itemVersion: null,
	origin: "EVENT",
	status: "COMPLETE",
	createdAt,
	completedAt: createdAt,
});

describe("groupByDay", () => {
	it("keeps the wire's order and never repeats a heading", () => {
		const days = groupByDay(
			[
				review("a", new Date(2026, 8, 22, 18, 0)),
				review("b", new Date(2026, 8, 22, 9, 0)),
				review("c", new Date(2026, 8, 21, 9, 0)),
				review("d", new Date(2025, 11, 31, 9, 0)),
			],
			(entry) => entry.createdAt,
			NOW,
		);
		expect(days.map((day) => [day.label, day.rows.map((entry) => entry.id)])).toStrictEqual([
			["Tuesday, 22 September", ["a", "b"]],
			["Monday, 21 September", ["c"]],
			["Wednesday, 31 December 2025", ["d"]],
		]);
	});
});

describe("reviewOrdinalLabel", () => {
	it.each([
		[1, undefined],
		[2, "Review 2"],
		[3, "Review 3"],
		[4, "Review 4"],
		[11, "Review 11"],
		[12, "Review 12"],
		[112, "Review 112"],
	])("labels position %i as %s", (position, label) => {
		expect(reviewOrdinalLabel(position)).toBe(label);
	});

	it("counts the item's reviews when it knows them", () => {
		expect(reviewOrdinalLabel(2, 3)).toBe("Review 2 of 3");
		expect(reviewOrdinalLabel(4, 3)).toBe("Review 4");
	});

	it("names the first run too once the work has more than one", () => {
		expect(reviewOrdinalLabel(1, 2)).toBe("Review 1 of 2");
		expect(reviewOrdinalLabel(1, 1)).toBeUndefined();
		expect(reviewOrdinalLabel(1)).toBeUndefined();
	});
});
