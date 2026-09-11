import { describe, expect, it } from "vitest";

import {
	emptySurveyDraft,
	hasDraftErrors,
	newQuestionDraft,
	type QuestionDraft,
	type SurveyDraft,
	toCreateSurvey,
	validateSurveyDraft,
} from "./admin-survey-draft";

const NOW = Date.UTC(2026, 8, 11, 12, 0, 0);

function question(overrides: Partial<QuestionDraft> = {}): QuestionDraft {
	return { ...newQuestionDraft("q1"), prompt: "How is it going?", ...overrides };
}

function draft(overrides: Partial<SurveyDraft> = {}): SurveyDraft {
	return {
		...emptySurveyDraft("q1"),
		title: "Check-in",
		description: "Decide what to fix first.",
		questions: [question()],
		...overrides,
	};
}

describe("validateSurveyDraft", () => {
	it("accepts a titled, described survey with one free-text question", () => {
		expect(hasDraftErrors(validateSurveyDraft(draft(), NOW))).toBe(false);
	});

	it("names the empty fields", () => {
		const errors = validateSurveyDraft(emptySurveyDraft("q1"), NOW);
		expect(errors.title).toBe("Give the survey a title.");
		expect(errors.description).toBe("Say what the answers are for.");
		expect(errors.questionErrors).toStrictEqual([{ prompt: "Write the question." }]);
	});

	it("refuses a survey with no questions", () => {
		expect(validateSurveyDraft(draft({ questions: [] }), NOW).questions).toBe(
			"Add at least one question.",
		);
	});

	it.each([
		["one choice", "Yes", "Enter 2–20 choices, one per line."],
		["a repeated choice", "Yes\nYes", "Each choice must be different."],
		[
			"a choice over 200 characters",
			`Yes\n${"n".repeat(201)}`,
			"Keep each choice to 200 characters or fewer.",
		],
	])("refuses a choice question with %s", (_case, choices, message) => {
		const errors = validateSurveyDraft(
			draft({ questions: [question({ type: "SINGLE_CHOICE", choices })] }),
			NOW,
		);
		expect(errors.questionErrors[0]?.choices).toBe(message);
	});

	it("reads choices one per line, trimmed, and ignores blank lines", () => {
		const errors = validateSurveyDraft(
			draft({ questions: [question({ type: "MULTIPLE_CHOICE", choices: " Yes \n\nNo\n" })] }),
			NOW,
		);
		expect(errors.questionErrors[0]).toStrictEqual({});
	});

	it("requires both scale labels on a rating question", () => {
		const errors = validateSurveyDraft(
			draft({ questions: [question({ type: "RATING", lowLabel: "Not useful" })] }),
			NOW,
		);
		expect(errors.questionErrors[0]).toStrictEqual({
			highLabel: "Label the high end of the scale.",
		});
	});

	it("measures a blank start against the publish time", () => {
		const past = new Date(NOW - 60_000).toISOString().slice(0, 16);
		expect(validateSurveyDraft(draft({ endsAt: past }), NOW).endsAt).toBe(
			"The end must be after the start.",
		);
		expect(validateSurveyDraft(draft({ endsAt: "2030-01-01T09:00" }), NOW).endsAt).toBeUndefined();
	});

	it("measures an end against an explicit start", () => {
		const errors = validateSurveyDraft(
			draft({ startsAt: "2030-01-02T09:00", endsAt: "2030-01-01T09:00" }),
			NOW,
		);
		expect(errors.endsAt).toBe("The end must be after the start.");
	});
});

describe("toCreateSurvey", () => {
	const publishedAt = new Date(NOW);

	it("sends only what each question type accepts", () => {
		const body = toCreateSurvey(
			draft({
				questions: [
					question({ id: "text", prompt: " Anything else? " }),
					question({
						id: "choice",
						type: "SINGLE_CHOICE",
						choices: "On the pull request\nOn my practice page",
						lowLabel: "ignored",
						highLabel: "ignored",
						allowOther: true,
					}),
					question({
						id: "rating",
						type: "RATING",
						lowLabel: " Not useful ",
						highLabel: "Very useful",
						required: true,
					}),
					question({ id: "nps", type: "NPS", choices: "ignored", allowOther: true }),
				],
			}),
			publishedAt,
		);
		expect(body.questions).toStrictEqual([
			{
				id: "text",
				prompt: "Anything else?",
				type: "TEXT",
				options: [],
				required: false,
				allowOther: false,
			},
			{
				id: "choice",
				prompt: "How is it going?",
				type: "SINGLE_CHOICE",
				options: ["On the pull request", "On my practice page"],
				required: false,
				allowOther: true,
			},
			{
				id: "rating",
				prompt: "How is it going?",
				type: "RATING",
				options: [],
				required: true,
				allowOther: false,
				lowLabel: "Not useful",
				highLabel: "Very useful",
			},
			{
				id: "nps",
				prompt: "How is it going?",
				type: "NPS",
				options: [],
				required: false,
				allowOther: false,
			},
		]);
	});

	it("opens at the publish time when no start is set and to every workspace by default", () => {
		const body = toCreateSurvey(draft(), publishedAt);
		expect(body.startsAt).toBe(publishedAt);
		expect(body.endsAt).toBeUndefined();
		expect(body.workspaceId).toBeUndefined();
	});

	it("converts the device-local schedule and the chosen audience", () => {
		const body = toCreateSurvey(
			draft({ audience: "7", startsAt: "2030-01-01T09:00", endsAt: "2030-01-08T09:00" }),
			publishedAt,
		);
		expect(body.workspaceId).toBe(7);
		expect(body.startsAt).toStrictEqual(new Date("2030-01-01T09:00"));
		expect(body.endsAt).toStrictEqual(new Date("2030-01-08T09:00"));
	});
});
