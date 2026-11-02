import { describe, expect, it } from "vitest";

import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";

import { ownCardForTour, suggestedChapter } from "./tour-chapters";
import { exampleCard } from "./tour-example";

const open = exampleCard(new Date("2026-11-02T10:00:00Z"));
const card = (override: Partial<PracticeFeedbackCardEntry>): PracticeFeedbackCardEntry => ({
	...open,
	...override,
});

describe("ownCardForTour", () => {
	it("stands on an open card with work to point at", () => {
		expect(ownCardForTour([open])).toBe(open);
	});

	it.each([
		["a resolved card", card({ state: "resolved" })],
		["a card whose history cannot be verified", card({ unverifiedHistory: true })],
		["a card with no reviewed work", card({ reviewedWork: [] })],
	])("does not stand on %s", (_, entry) => {
		expect(ownCardForTour([entry])).toBeUndefined();
	});
});

describe("suggestedChapter", () => {
	it("starts on the chain without an open card, and on the profile with one", () => {
		expect(suggestedChapter([])).toBe("how-it-works");
		expect(suggestedChapter([card({ state: "withdrawn" })])).toBe("how-it-works");
		expect(suggestedChapter([open])).toBe("your-profile");
	});
});
