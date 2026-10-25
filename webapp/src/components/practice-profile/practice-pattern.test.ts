import { describe, expect, it } from "vitest";

import { cleanProgress } from "@/components/practice-vocabulary/practice-feedback-timeline";
import { SAMPLE_PATTERN, sampleOpenCards } from "@/stories/practice-profile-samples";
import { practicePatternOf } from "./practice-pattern";

const NOW = Date.UTC(2026, 9, 8, 12);

/** The first sample card and the first piece of work it rests on. */
function firstCardAndPiece() {
	const [first] = sampleOpenCards(NOW);
	const piece = first?.reviewedWork[0];
	if (first === undefined || piece === undefined) {
		throw new Error("The samples carry an open card with its work");
	}
	return { first, piece };
}

describe("practicePatternOf", () => {
	it("reads the pattern from the practice's open card in the card's own counts", () => {
		const cards = sampleOpenCards(NOW);
		expect(practicePatternOf(cards, "describe-what-and-why")).toStrictEqual({
			cleanCount: 1,
			cleanNeeded: 3,
		});
		expect(cleanProgress(SAMPLE_PATTERN)).toBe("1 clean in a row · 2 more resolve it");
	});

	it("reads the pattern from an open card that rests on one piece of work", () => {
		const { first, piece } = firstCardAndPiece();
		const pattern = { cleanCount: first.cleanWork.length, cleanNeeded: first.cleanNeeded };
		const onePiece = { ...first, reviewedWork: [piece] };
		expect(practicePatternOf([onePiece], onePiece.practiceSlug)).toStrictEqual(pattern);
		// The same pull request twice is one piece of work, and still an open card.
		const twiceTheSame = { ...first, reviewedWork: [piece, { ...piece }] };
		expect(practicePatternOf([twiceTheSame], twiceTheSame.practiceSlug)).toStrictEqual(pattern);
	});

	it("has no pattern for a practice without an open card", () => {
		const closed = sampleOpenCards(NOW).map((card) => ({ ...card, state: "resolved" as const }));
		expect(practicePatternOf(closed, "describe-what-and-why")).toBeUndefined();
		expect(practicePatternOf(sampleOpenCards(NOW), "unknown")).toBeUndefined();
	});
});
