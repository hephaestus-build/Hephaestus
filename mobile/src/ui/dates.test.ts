import { describe, expect, it } from "vitest";

import { formatRelative } from "./dates";

const NOW = Date.UTC(2026, 8, 26, 12, 0, 0);

describe("formatRelative", () => {
	it("says just now for the last minute", () => {
		expect(formatRelative(new Date(NOW - 20_000), NOW)).toBe("just now");
	});

	it("counts hours and days in words within a week", () => {
		expect(formatRelative(new Date(NOW - 3 * 3_600_000), NOW)).toBe("3 hours ago");
		expect(formatRelative(new Date(NOW - 24 * 3_600_000), NOW)).toBe("1 day ago");
		expect(formatRelative(new Date(NOW - 3 * 24 * 3_600_000), NOW)).toBe("3 days ago");
		expect(formatRelative(new Date(NOW - 60_000), NOW)).toBe("1 minute ago");
	});

	it("names the date once it is older than a week", () => {
		expect(formatRelative(new Date(Date.UTC(2026, 7, 1, 12)), NOW)).toContain("2026");
	});
});
