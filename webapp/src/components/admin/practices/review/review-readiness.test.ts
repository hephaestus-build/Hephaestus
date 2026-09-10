import { describe, expect, it } from "vitest";

import type { AgentBinding } from "@/api/types.gen";
import { availableReviewBinding, reviewModelRunnable } from "./review-readiness";

describe("workspace review model readiness", () => {
	it("recognizes an available member location even when the legacy default is unavailable", () => {
		const unavailable: AgentBinding = {
			purpose: "PRACTICE_REVIEW",
			processingLocation: "UNCLASSIFIED",
			enabled: true,
			ready: false,
		};
		const onPremises: AgentBinding = {
			purpose: "PRACTICE_REVIEW",
			processingLocation: "ON_PREMISES",
			enabled: true,
			ready: true,
		};
		const binding = availableReviewBinding([unavailable, onPremises]);
		expect(binding).toBe(onPremises);
		expect(reviewModelRunnable({ status: "ready", binding })).toBe(true);
	});
	it("does not mistake a ready mentor assignment for a practice-review model", () => {
		expect(
			availableReviewBinding([
				{ purpose: "MENTOR", processingLocation: "ON_PREMISES", enabled: true, ready: true },
			]),
		).toBeUndefined();
	});
});
