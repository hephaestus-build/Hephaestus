import { describe, expect, it } from "vitest";

import { holdReasonCopy, isResultProcessingRetryable, jobWait } from "./job-utils";

const NOW = new Date("2026-05-20T12:00:00Z").getTime();
const SOON = new Date("2026-05-20T12:05:00Z");
const EARLIER = new Date("2026-05-20T11:55:00Z");

describe("jobWait", () => {
	it("reports a hold whenever the server names a reason", () => {
		expect(
			jobWait({ status: "QUEUED", holdReason: "BUDGET", availableAt: SOON }, NOW),
		).toStrictEqual({
			kind: "hold",
			reason: "BUDGET",
		});
	});

	it("still reports a hold once the parked instant has lapsed", () => {
		expect(
			jobWait({ status: "QUEUED", holdReason: "BUDGET", availableAt: EARLIER }, NOW),
		).toStrictEqual({
			kind: "hold",
			reason: "BUDGET",
		});
	});

	it("reports a backoff for a queued run whose next attempt is still ahead", () => {
		expect(
			jobWait({ status: "QUEUED", holdReason: undefined, availableAt: SOON }, NOW),
		).toStrictEqual({
			kind: "backoff",
		});
	});

	it("reports nothing for a queued run that is already claimable", () => {
		expect(
			jobWait({ status: "QUEUED", holdReason: undefined, availableAt: EARLIER }, NOW),
		).toBeNull();
	});

	it("reports nothing once the run has left the queue, whatever its timestamps say", () => {
		expect(
			jobWait({ status: "RUNNING", holdReason: undefined, availableAt: SOON }, NOW),
		).toBeNull();
		expect(
			jobWait({ status: "COMPLETED", holdReason: "BUDGET", availableAt: SOON }, NOW),
		).toBeNull();
	});
});

describe("holdReasonCopy", () => {
	it("names the reason it knows in words an operator can act on", () => {
		expect(holdReasonCopy("BUDGET").label).toBe("Over the AI budget");
		expect(holdReasonCopy("BUDGET").detail).toMatch(/resumes on its own/u);
	});

	it("reads a reason it has never seen as English rather than as a constant", () => {
		expect(holdReasonCopy("MODEL_UNAVAILABLE").label).toBe("Model unavailable");
		expect(holdReasonCopy("MODEL_UNAVAILABLE").detail).toMatch(/resumes on its own/u);
	});

	it("never suggests a held run failed", () => {
		for (const reason of ["BUDGET", "MODEL_UNAVAILABLE"]) {
			expect(holdReasonCopy(reason).detail).toMatch(/rather than failed/u);
		}
	});
});

describe("result-processing retry", () => {
	it.each([
		["COMPLETED", "FAILED", true],
		["COMPLETED", "PENDING", false],
		["COMPLETED", "DELIVERED", false],
		["RUNNING", "FAILED", false],
		["FAILED", "FAILED", false],
	] as const)("%s / %s is retryable: %s", (status, deliveryStatus, retryable) => {
		expect(isResultProcessingRetryable({ status, deliveryStatus })).toBe(retryable);
	});
});
