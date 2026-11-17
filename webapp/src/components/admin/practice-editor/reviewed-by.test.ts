import { describe, expect, it } from "vitest";

import { reviewedByProblem } from "./reviewed-by";

describe("reviewedByProblem", () => {
	const draft = { by: "HEPHAESTUS" as const, humanReason: { description: "" }, limits: [] };

	it("needs a reason only from people", () => {
		expect(reviewedByProblem(draft)).toBeUndefined();
		expect(reviewedByProblem({ ...draft, by: "NOBODY" })).toBeUndefined();
		expect(reviewedByProblem({ ...draft, by: "PEOPLE" })?.focusId).toBe("practice-human-reason");
		expect(
			reviewedByProblem({
				...draft,
				by: "PEOPLE",
				humanReason: { description: "Decided in a meeting." },
			}),
		).toBeUndefined();
	});

	it("refuses a blank or repeated limit while Hephaestus reviews the practice", () => {
		expect(reviewedByProblem({ ...draft, limits: [{ description: " " }] })?.focusId).toBe(
			"practice-limit-description-0",
		);
		const limit = { description: "Runtime behavior not observed" };
		expect(reviewedByProblem({ ...draft, limits: [limit, limit] })?.message).toBe(
			"Describe each limit once.",
		);
		expect(
			reviewedByProblem({ ...draft, by: "NOBODY", limits: [{ description: " " }] }),
		).toStrictEqual({
			message: "A kept review limit needs a description. Choose Hephaestus to edit or remove it.",
			focusId: "practice-reviewed-by",
		});
	});
});
