import { describe, expect, it } from "vitest";

import { weekStarts } from "./activity-tally";

describe("weekStarts", () => {
	it("starts at the UTC Monday of the first day and lists every week up to the end", () => {
		expect(
			weekStarts(new Date("2026-09-03T15:00:00Z"), new Date("2026-09-21T00:00:00Z")).map((start) =>
				start.toISOString(),
			),
		).toStrictEqual([
			"2026-08-31T00:00:00.000Z",
			"2026-09-07T00:00:00.000Z",
			"2026-09-14T00:00:00.000Z",
		]);
	});
});
