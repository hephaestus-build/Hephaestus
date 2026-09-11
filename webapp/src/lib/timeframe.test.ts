import { addDays, addWeeks, startOfMonth } from "date-fns";
import { describe, expect, it } from "vitest";

import {
	DEFAULT_SCHEDULE,
	detectPresetFromDates,
	formatDateRangeForApi,
	getDateRangeForPreset,
} from "./timeframe";

const now = new Date(2026, 8, 16, 12);

describe("timeframe preset detection", () => {
	it("uses the same all-time boundary in every timezone", () => {
		expect(getDateRangeForPreset(now, "all-activity").after.toISOString()).toBe(
			"1970-01-01T00:00:00.000Z",
		);
	});

	it("recognizes bounded leaderboard periods using the configured schedule", () => {
		const schedule = { day: 4, hour: 13, minute: 30 };
		const week = getDateRangeForPreset(now, "this-week", schedule).after;
		expect(
			detectPresetFromDates(now, week.toISOString(), addWeeks(week, 1).toISOString(), schedule),
		).toBe("this-week");
		const month = startOfMonth(now);
		const nextMonth = new Date(2026, 9, 1);
		expect(detectPresetFromDates(now, month.toISOString(), nextMonth.toISOString(), schedule)).toBe(
			"this-month",
		);
	});

	it.each(["all-activity", "this-week", "last-week", "this-month", "last-month"] as const)(
		"recognizes %s after serialization, including equivalent UTC offsets",
		(preset) => {
			const range = getDateRangeForPreset(now, preset);
			const dates = formatDateRangeForApi(range);
			expect(detectPresetFromDates(now, dates.after, dates.before, DEFAULT_SCHEDULE, true)).toBe(
				preset,
			);
			expect(
				detectPresetFromDates(
					now,
					range.after.toISOString(),
					range.before?.toISOString(),
					DEFAULT_SCHEDULE,
					true,
				),
			).toBe(preset);
		},
	);

	it.each(["this-week", "last-week", "this-month", "last-month"] as const)(
		"keeps custom dates near %s editable instead of calling them a preset",
		(preset) => {
			const range = getDateRangeForPreset(now, preset);
			const dates = formatDateRangeForApi({ ...range, after: addDays(range.after, 1) });
			expect(detectPresetFromDates(now, dates.after, dates.before)).toBe("custom");
		},
	);

	it("does not label a bounded epoch selection as all time", () => {
		expect(
			detectPresetFromDates(
				now,
				new Date(0).toISOString(),
				new Date(2020, 0, 1).toISOString(),
				DEFAULT_SCHEDULE,
				true,
			),
		).toBe("custom");
	});
});
