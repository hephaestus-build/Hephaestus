import { describe, expect, it } from "vitest";

import type { AgentBinding } from "@/api/types.gen";

import {
	availableReviewBinding,
	reviewModelRunnable,
	reviewReadinessDescription,
	unservedReviewTiers,
} from "./review-readiness";

const undeclared: AgentBinding = {
	purpose: "PRACTICE_REVIEW",
	dataHandlingTier: "UNDECLARED",
	enabled: true,
	ready: false,
	servedTiers: [],
};
const inHouse: AgentBinding = {
	purpose: "PRACTICE_REVIEW",
	dataHandlingTier: "IN_HOUSE",
	enabled: true,
	ready: true,
	servedTiers: ["IN_HOUSE"],
};
const cloud: AgentBinding = {
	purpose: "PRACTICE_REVIEW",
	dataHandlingTier: "CLOUD",
	enabled: true,
	ready: true,
	servedTiers: ["CLOUD"],
};

describe("workspace review model readiness", () => {
	it("recognizes a ready declared tier even when the undeclared slot is not ready", () => {
		const binding = availableReviewBinding([undeclared, inHouse]);
		expect(binding).toBe(inHouse);
		expect(reviewModelRunnable({ status: "ready", binding })).toBe(true);
	});

	it("names the strictest ready tier, the one within the most developers' choices", () => {
		expect(availableReviewBinding([cloud, inHouse])).toBe(inHouse);
	});

	it("does not mistake a ready conversation assignment for a practice-review model", () => {
		expect(
			availableReviewBinding([
				{
					purpose: "MENTOR",
					dataHandlingTier: "IN_HOUSE",
					enabled: true,
					ready: true,
					servedTiers: ["IN_HOUSE", "CLOUD"],
				},
			]),
		).toBeUndefined();
	});

	it("says which declared tier is in force while reviews run", () => {
		expect(
			reviewReadinessDescription({ enabled: true, model: { status: "ready", binding: inHouse } }),
		).toBe(
			"Practice reviews are on and a review model declared as In-house is ready. Each developer’s AI choice still decides whether it runs for them. Feedback mode decides separately where observations become feedback.",
		);
		expect(
			reviewReadinessDescription({
				enabled: true,
				model: { status: "ready", binding: { ...undeclared, ready: true } },
			}),
		).toMatch(/ready for members who have not chosen/u);
		expect(reviewReadinessDescription({ enabled: true, model: { status: "ready" } })).toMatch(
			/no review model is ready/u,
		);
	});
});

describe("member tiers without a review model", () => {
	it("names each AI choice that no review binding serves, by the server's served tiers", () => {
		expect(unservedReviewTiers([undeclared, inHouse], false)).toStrictEqual([
			"CLOUD",
			"UNDECLARED",
		]);
		expect(unservedReviewTiers([inHouse, cloud], false)).toStrictEqual(["UNDECLARED"]);
	});

	it("leaves out the members who have not chosen once the workspace requires the choice", () => {
		expect(unservedReviewTiers([inHouse, cloud], true)).toStrictEqual([]);
	});

	it("counts no other purpose as a review model", () => {
		expect(unservedReviewTiers([{ ...cloud, purpose: "MENTOR" }], true)).toStrictEqual([
			"IN_HOUSE",
			"CLOUD",
		]);
	});
});
