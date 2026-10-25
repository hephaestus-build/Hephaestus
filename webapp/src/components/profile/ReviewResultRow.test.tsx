import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { EvidenceCitation, ResultDetail } from "@/api/types.gen";
import { detailResult, listedResult } from "@/stories/practice-detail-story-mock-data";

import { ReviewResultRow, type ReviewResultRowProps } from "./ReviewResultRow";

const result: ResultDetail = {
	...detailResult,
	id: "00000000-0000-0000-0000-000000000001",
	practiceSlug: "explain-decisions",
	practiceName: "Explain significant decisions",
	summary: "The reason for the timeout is missing",
	outcome: "NOT_MET",
	severity: "MINOR",
	problems: [],
	feedback: [],
};

function renderOpen(props: Partial<ReviewResultRowProps> = {}) {
	return render(
		<ul>
			<ReviewResultRow result={result} {...props} />
		</ul>,
	);
}

const citation = (path: string): EvidenceCitation => ({
	sourceKind: "scm.pull-request.diff",
	sourcePath: "owner/repo#1",
	path,
	side: "NEW",
	commit: null,
	startLine: 1,
	endLine: 1,
	quote: "return null;",
	verification: null,
});

const core = (quote: string): EvidenceCitation => ({
	...citation("inputs/context/metadata.json"),
	sourceKind: "scm.pull-request.core",
	side: null,
	quote,
});

const NO_EVIDENCE = { citations: [], openQuestions: [], search: null, inapplicability: null };

describe("ReviewResultRow", () => {
	it("keeps a diff pair that could not fold as two blocks, each with its own key", () => {
		// React reports key collisions only through console diagnostics.
		using keyWarning = vi.spyOn(console, "error").mockReturnValue(undefined);
		renderOpen({
			result: {
				...result,
				evidence: {
					...NO_EVIDENCE,
					citations: [
						{ ...citation("src/config.ts"), side: "OLD", quote: "const timeout = 30;" },
						{ ...citation("src/config.ts"), side: "NEW", quote: null },
					],
				},
			},
		});

		expect(keyWarning).not.toHaveBeenCalled();
		expect(screen.getAllByText("config.ts")).toHaveLength(2);
		screen.getByText("const timeout = 30;");
	});

	it("quotes each problem's place under that problem, never folded into another's", () => {
		renderOpen({
			result: {
				...result,
				problems: [
					{
						id: "a",
						resultId: result.id,
						position: 0,
						key: null,
						severity: "MAJOR",
						summary: "The title says nothing",
						explanation: "It names the ticket only.",
						citations: [core('"title" : "PROJ-12"')],
						basis: null,
						nextStep: null,
					},
					{
						id: "b",
						resultId: result.id,
						position: 1,
						key: null,
						severity: "MINOR",
						summary: "The body is empty",
						explanation: "There is no description.",
						citations: [core('"body" : ""')],
						basis: null,
						nextStep: null,
					},
				],
			},
		});

		const problems = screen.getAllByRole("listitem").filter((item) => item.closest("ol") !== null);
		expect(problems).toHaveLength(2);
		expect(problems[0]?.textContent).toContain('"title" : "PROJ-12"');
		expect(problems[0]?.textContent).not.toContain('"body" : ""');
		expect(problems[1]?.textContent).toContain('"body" : ""');
		screen.getByText("Not met · 2 problems");
	});

	it("lists what could not be settled as open questions, never as a check", () => {
		renderOpen({
			result: {
				...result,
				outcome: "COULD_NOT_DETERMINE",
				evidence: {
					...NO_EVIDENCE,
					openQuestions: [
						{
							openQuestion: "Was the move requested?",
							wouldSettleIt: "The linked issue.",
							citation: null,
						},
					],
				},
			},
		});

		screen.getByText("Open questions");
		screen.getByText("Was the move requested?");
		screen.getByText("The linked issue.");
		expect(screen.queryByText("What was checked")).toBeNull();
	});

	it("names a met result without a summary by its practice, once", () => {
		renderOpen({ result: { ...result, outcome: "MET", summary: null } });

		expect(screen.getAllByText("Explain significant decisions")).toHaveLength(1);
		screen.getByText("Met");
	});

	it("shows a listed result's problems by summary, with no proof and no response", () => {
		renderOpen({ result: listedResult(detailResult) });

		screen.getByText("Not met · 3 problems");
		screen.getByText("The caching change hides inside the package move");
		expect(screen.queryByText("Why it was noted")).toBeNull();
		expect(screen.queryByText("Evidence")).toBeNull();
		expect(screen.queryByRole("group")).toBeNull();
	});

	it("lists each piece of a result's feedback with where it went, and offers no response there", () => {
		renderOpen({
			result: {
				...detailResult,
				feedback: [
					...detailResult.feedback,
					{
						id: "00000000-0000-0000-0000-0000000001d1",
						destination: "PRACTICE_PAGE",
						state: "DELIVERED",
						deliveredAt: detailResult.recordedAt,
						response: { resolution: "ADDRESSED", usefulness: null, comment: null, createdAt: null },
						copies: [],
					},
				],
			},
		});

		const list = screen.getByRole("list", { name: "Feedback from this observation" });
		expect(within(list).getAllByRole("listitem")).toHaveLength(detailResult.feedback.length + 1);
		within(list).getByText("On the practice page");
		// A response already given reads as a line, not as controls.
		within(list).getByText("Your response:");
		expect(screen.queryByRole("group", { name: /^Your response to the feedback/u })).toBeNull();
	});
});
