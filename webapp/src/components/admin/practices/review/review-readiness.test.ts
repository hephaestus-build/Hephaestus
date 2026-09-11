import { describe, expect, it } from "vitest";

import type { AgentBinding } from "@/api/types.gen";

import {
	availableReviewBinding,
	reviewModelRunnable,
	reviewRunningDescription,
} from "./review-readiness";

const undeclared: AgentBinding = {
	purpose: "PRACTICE_REVIEW",
	dataHandlingTier: "UNDECLARED",
	enabled: true,
	ready: false,
};
const inHouse: AgentBinding = {
	purpose: "PRACTICE_REVIEW",
	dataHandlingTier: "IN_HOUSE",
	enabled: true,
	ready: true,
};
const providerKept: AgentBinding = {
	purpose: "PRACTICE_REVIEW",
	dataHandlingTier: "PROVIDER_KEPT",
	enabled: true,
	ready: true,
};

describe("workspace review model readiness", () => {
	it("recognizes a ready declared tier even when the undeclared slot is not ready", () => {
		const binding = availableReviewBinding([undeclared, inHouse]);
		expect(binding).toBe(inHouse);
		expect(reviewModelRunnable({ status: "ready", binding })).toBe(true);
	});

	it("names the strictest ready tier, the one within the most developers' choices", () => {
		expect(availableReviewBinding([providerKept, inHouse])).toBe(inHouse);
	});

	it("does not mistake a ready mentor assignment for a practice-review model", () => {
		expect(
			availableReviewBinding([
				{ purpose: "MENTOR", dataHandlingTier: "IN_HOUSE", enabled: true, ready: true },
			]),
		).toBeUndefined();
	});

	it("says which declared tier is in force while reviews run", () => {
		expect(
			reviewRunningDescription({ enabled: true, model: { status: "ready", binding: inHouse } }),
		).toBe(
			"Practice reviews are on and a review model declared as Stays in-house is ready. Each developer's AI choice still decides whether it runs for them.",
		);
		expect(
			reviewRunningDescription({
				enabled: true,
				model: { status: "ready", binding: { ...undeclared, ready: true } },
			}),
		).toMatch(/ready for members who have not chosen/);
		expect(reviewRunningDescription({ enabled: true, model: { status: "ready" } })).toMatch(
			/no review model is ready/,
		);
	});
});
