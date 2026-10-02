import { describe, expect, it } from "vitest";

import { heldBackSentence, type SplitContext, splitDescription } from "./across-workspace-copy";

const context: SplitContext = {
	window: "TERM",
	readerCounted: true,
	observedDevelopers: 24,
	minimumOthers: 5,
};

describe("splitDescription", () => {
	it("names the reference group, every count and the rest without a standing", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 5, mixedFeedback: 7, goingWell: 7 },
				"MIXED",
				context,
			),
		).toBe(
			"24 developers observed in this workspace this term: 5 Needs attention, 7 Mixed feedback, 7 Going well, 5 none yet. You: Mixed feedback.",
		);
	});

	it("says the reader is not counted when their standing is no part of the split", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 5, mixedFeedback: 7, goingWell: 7 },
				"NOT_OBSERVED",
				{ ...context, window: "DAYS_30" },
			),
		).toMatch(/in the last 30 days: .* You: Not observed yet, not counted in the split\.$/u);
	});

	it("gives the two parts of a collapsed split and why it collapsed", () => {
		expect(
			splitDescription({ shape: "COLLAPSED", hasStanding: 19, noneYet: 5 }, "STRENGTH", context),
		).toBe(
			"24 developers observed in this workspace this term: 19 have a standing, 5 none yet. The split is held back while one standing would cover fewer than 5 developers other than you. You: Going well.",
		);
	});
});

describe("heldBackSentence", () => {
	it("keeps the observed total while it holds enough others", () => {
		expect(heldBackSentence(context)).toBe("Split held back: 24 developers observed this term.");
	});

	it("drops the total when the others in it are fewer than K", () => {
		expect(heldBackSentence({ ...context, observedDevelopers: 5 })).toBe(
			"Too few developers observed to compare yet.",
		);
		expect(heldBackSentence({ ...context, observedDevelopers: 5, readerCounted: false })).toBe(
			"Split held back: 5 developers observed this term.",
		);
	});
});
