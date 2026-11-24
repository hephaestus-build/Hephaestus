import { expect, it } from "vite-plus/test";

import { formatCheckInTime } from "./conversation-copy";

it("names the check-in zone and formats the time in that zone", () => {
	const date = new Date("2026-11-24T08:30:00Z");
	expect(formatCheckInTime(date, "Europe/Berlin", "en-GB")).toContain("09:30");
	expect(formatCheckInTime(date, "Europe/Berlin", "en-GB")).toContain("Europe/Berlin");
});
