import { describe, expect, it } from "vitest";

import {
	clearPendingReport,
	REPORT_LIMIT,
	reportMessage,
	peekPendingReport,
	setPendingReport,
} from "./report";

describe("reportMessage", () => {
	it("says what is reported, why, and quotes the text", () => {
		const message = reportMessage(
			{ subject: "heph-reply", text: "First line\nSecond line" },
			"  It insulted me  ",
		);

		expect(message).toBe(
			"Reported in the mobile app: a reply from Heph.\n\nReason: It insulted me\n\nReported text:\n> First line\n> Second line",
		);
	});

	it("still sends a report without a reason", () => {
		expect(reportMessage({ subject: "practice-feedback", text: "x" }, " ")).toContain(
			"No reason given.",
		);
	});

	it("keeps the reason whole and shortens a long text to the field's limit, saying so", () => {
		const reason = "Harmful advice about security";
		const message = reportMessage({ subject: "heph-reply", text: "a".repeat(20_000) }, reason);

		expect(message.length).toBeLessThanOrEqual(REPORT_LIMIT);
		expect(message).toContain(reason);
		expect(message.endsWith("[…shortened to fit]")).toBe(true);
	});
});

describe("pending report", () => {
	it("is read by the report screen, then gone once it has opened", () => {
		setPendingReport({ subject: "heph-reply", text: "private reply" });

		expect(peekPendingReport()?.text).toBe("private reply");
		clearPendingReport();
		expect(peekPendingReport()).toBeUndefined();
	});

	it("is forgotten when the session changes", () => {
		setPendingReport({ subject: "heph-reply", text: "private reply" });
		clearPendingReport();

		expect(peekPendingReport()).toBeUndefined();
	});
});
