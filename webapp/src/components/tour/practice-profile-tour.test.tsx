import { describe, expect, it } from "vitest";

import { PRACTICE_PROFILE_TOUR, practiceProfileTourSections } from "./practice-profile-tour";
import { tourMinutes } from "./ProductTour";

describe("practice profile tour outline", () => {
	const sections = practiceProfileTourSections();
	const parts = sections.flatMap((section) => section.parts);

	it("splits every section step into exactly one part, in order", () => {
		const covered = parts.flatMap((part) =>
			Array.from({ length: part.count }, (_, offset) => part.first + offset),
		);
		const sectionSteps = PRACTICE_PROFILE_TOUR.map((step, at) => ({ step, at }))
			.filter(({ step }) => step.chapter !== undefined)
			.map(({ at }) => at);
		expect(covered).toStrictEqual(sectionSteps);
	});

	it("keeps each part inside its own section", () => {
		for (const part of parts) {
			const names = PRACTICE_PROFILE_TOUR.slice(part.first, part.last + 1).map(
				(step) => step.chapter,
			);
			expect(new Set(names)).toStrictEqual(new Set([part.section]));
		}
	});

	it("takes How it works, Your profile and Compare as one block and splits Going deeper in four", () => {
		expect(sections.map((section) => section.parts.length)).toStrictEqual([1, 1, 4, 1]);
		expect(sections.map((section) => section.count)).toStrictEqual([9, 7, 17, 7]);
		expect(parts[0]?.id).toBe("How it works");
		expect(parts[2]?.id).toBe("Going deeper/All practice groups");
	});

	it("estimates about 15 seconds a step, rounded up to whole minutes", () => {
		expect(tourMinutes(1)).toBe("about 1 min");
		expect(tourMinutes(5)).toBe("about 2 min");
		expect(tourMinutes(17)).toBe("about 5 min");
	});
});
