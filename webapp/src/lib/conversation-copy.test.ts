import { expect, it } from "vite-plus/test";

import { formatCheckInTime, conversationLabel } from "./conversation-copy";

it("names the check-in zone and formats the time in that zone", () => {
	const date = new Date("2026-11-24T08:30:00Z");
	expect(formatCheckInTime(date, "Europe/Berlin", "en-GB")).toContain("09:30");
	expect(formatCheckInTime(date, "Europe/Berlin", "en-GB")).toContain("Europe/Berlin");
});

it("shows the fallback for a future code", () => {
	expect(
		conversationLabel({ code: "notice.future-code", text: "A new notice is available." }),
	).toBe("A new notice is available.");
});
