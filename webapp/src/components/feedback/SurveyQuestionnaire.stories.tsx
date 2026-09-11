import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { Question } from "@/api/types.gen";
import { Stateful } from "@/stories/stateful";

import { surveyQuestions } from "./product-survey-fixtures";
import { EMPTY_SURVEY_RESPONSE_DRAFT } from "./survey-questions";
import { SurveyQuestionnaire } from "./SurveyQuestionnaire";

const question = (
	overrides: Partial<Question> & Pick<Question, "id" | "prompt" | "type">,
): Question => ({
	options: [],
	required: false,
	allowOther: false,
	...overrides,
});

const meta = {
	title: "Product feedback/Survey questionnaire",
	component: SurveyQuestionnaire,
	args: {
		questions: surveyQuestions,
		draft: EMPTY_SURVEY_RESPONSE_DRAFT,
		onDraftChange: fn(),
		onSubmit: fn(),
		children: null,
	},
	argTypes: { children: { table: { disable: true } } },
	render: (args) => (
		<Stateful initial={args.draft}>
			{(draft, setDraft) => (
				<SurveyQuestionnaire
					{...args}
					draft={draft}
					onDraftChange={(next) => {
						args.onDraftChange(next);
						setDraft(next);
					}}
					className="max-w-lg gap-5"
				>
					<SurveyQuestionnaire.Progress />
					<SurveyQuestionnaire.Items />
					<SurveyQuestionnaire.Actions submitLabel="Send answers" />
				</SurveyQuestionnaire>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof SurveyQuestionnaire>;
export default meta;
type Story = StoryObj<typeof meta>;

/** Rating, single choice with a written-in answer, recommendation, free text — one at a time. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 1 of 4");
		await expect(canvas.queryByRole("button", { name: "Previous" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Skip" })).toBeNull();
	},
};

/** A required question refuses to move on until it has an answer, and says so next to the choices. */
export const RequiredQuestionBlocksNext: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await expect(canvas.getByRole("alert")).toHaveTextContent("Choose an answer to continue.");
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 1 of 4");
		await userEvent.click(canvas.getByRole("radio", { name: "4" }));
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 2 of 4");
	},
};

/** An optional question is passed with Skip, not with an empty Next. */
export const OptionalQuestionCanBeSkipped: Story = {
	args: { draft: { answers: { useful: "4" }, item: "channel" } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 2 of 4");
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"Choose an answer, or skip this question.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 3 of 4");
		await expect(args.onDraftChange).toHaveBeenLastCalledWith(
			expect.objectContaining({ item: "recommend" }),
		);
	},
};

/** Every answer is read back from the form on submit, in the field its question type expects. */
export const AnswersEveryQuestionAndSubmits: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "5" }));
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await userEvent.type(canvas.getByRole("textbox", { name: "Another answer" }), "In the CLI");
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await userEvent.click(canvas.getByRole("radio", { name: "9" }));
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await userEvent.type(canvas.getByRole("textbox", { name: "Your answer" }), "Less noise.");
		await userEvent.click(canvas.getByRole("button", { name: "Send answers" }));
		await expect(args.onSubmit).toHaveBeenCalledWith([
			{ questionId: "useful", rating: 5 },
			{ questionId: "channel", choices: ["In the CLI"] },
			{ questionId: "recommend", rating: 9 },
			{ questionId: "improve", text: "Less noise." },
		]);
	},
};

/** Closing and reopening lands on the question the member left, with their answers in place. */
export const ResumesFromADraft: Story = {
	args: {
		draft: {
			answers: { useful: "3", channel: "On my practice page" },
			item: "recommend",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 3 of 4");
		await userEvent.click(canvas.getByRole("button", { name: "Previous" }));
		await expect(canvas.getByRole("radio", { name: "On my practice page" })).toBeChecked();
		await userEvent.click(canvas.getByRole("button", { name: "Previous" }));
		await expect(canvas.getByRole("radio", { name: "3" })).toBeChecked();
	},
};

/** Number keys pick a scale point and Enter continues, so a rating takes two keystrokes. */
export const NumberShortcutsOnARating: Story = {
	args: { questions: surveyQuestions.filter((q) => q.type !== "NPS") },
	play: async ({ canvas, args }) => {
		canvas.getByRole("radio", { name: "1" }).focus();
		await userEvent.keyboard("4");
		await expect(canvas.getByRole("radio", { name: "4" })).toBeChecked();
		await userEvent.keyboard("{Enter}");
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 2 of 3");
		await expect(args.onDraftChange).toHaveBeenCalledWith(
			expect.objectContaining({ answers: { useful: "4" } }),
		);
	},
};

const wordChoices = [
	question({
		id: "channel",
		prompt: "Where do you read feedback most often?",
		type: "SINGLE_CHOICE",
		options: ["On the pull request", "On my practice page", "In conversation with Heph"],
		required: true,
	}),
	question({
		id: "wants",
		prompt: "What would you like more of?",
		type: "MULTIPLE_CHOICE",
		options: ["Shorter feedback", "Links to the practice", "Examples from my own code"],
		allowOther: true,
	}),
];

/** Without a scale in the survey, choices get letter shortcuts, and the badge says which. */
export const LetterShortcutsOnChoices: Story = {
	args: { questions: wordChoices },
	play: async ({ canvas }) => {
		const second = canvas.getByRole("radio", { name: /On my practice page/ });
		await expect(second.closest("label")).toHaveAttribute("data-shortcut", "B");
		canvas.getByRole("radio", { name: /On the pull request/ }).focus();
		await userEvent.keyboard("b");
		await expect(second).toBeChecked();
	},
};

/** Several choices and a written-in answer travel together as one list of choices. */
export const MultipleChoiceWithAnotherAnswer: Story = {
	args: {
		questions: wordChoices,
		draft: { answers: { channel: "On the pull request" }, item: "wants" },
	},
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("checkbox", { name: /Shorter feedback/ }));
		await userEvent.click(canvas.getByRole("checkbox", { name: /Examples from my own code/ }));
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Another answer" }),
			"A weekly digest",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Send answers" }));
		await expect(args.onSubmit).toHaveBeenCalledWith([
			{ questionId: "channel", choices: ["On the pull request"] },
			{
				questionId: "wants",
				choices: ["Shorter feedback", "Examples from my own code", "A weekly digest"],
			},
		]);
	},
};

/** A recommendation scale has eleven points and no shortcuts: keys start at 1, the scale at 0. */
export const RecommendationScale: Story = {
	args: {
		questions: [
			question({
				id: "recommend",
				prompt: "How likely are you to recommend Hephaestus to another team?",
				type: "NPS",
				required: true,
			}),
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("radio")).toHaveLength(11);
		await expect(canvas.getByText(/0 = Not at all likely · 10 = Extremely likely/)).toBeVisible();
		await expect(canvas.queryByText("A")).toBeNull();
		await userEvent.click(canvas.getByRole("radio", { name: "0" }));
		await expect(canvas.getByRole("radio", { name: "0" })).toBeChecked();
	},
};

/** A free-text answer keeps its line breaks: Enter stays in the field, Ctrl+Enter moves on. */
export const FreeTextKeepsLineBreaks: Story = {
	args: {
		questions: [
			question({ id: "why", prompt: "What would make it more useful?", type: "TEXT" }),
			question({ id: "more", prompt: "Anything else?", type: "TEXT" }),
		],
	},
	play: async ({ canvas, args }) => {
		const field = canvas.getByRole("textbox", { name: "Your answer" });
		await userEvent.type(field, "First line{Enter}Second line");
		await expect(field).toHaveValue("First line\nSecond line");
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 1 of 2");
		await userEvent.keyboard("{Control>}{Enter}{/Control}");
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 2 of 2");
		await expect(args.onDraftChange).toHaveBeenCalledWith(
			expect.objectContaining({ answers: { why: "First line\nSecond line" } }),
		);
	},
};

/** A survey with one question goes straight to Send. */
export const SingleQuestion: Story = {
	args: {
		questions: [
			question({
				id: "useful",
				prompt: "How useful is the practice feedback you receive?",
				type: "RATING",
				required: true,
				lowLabel: "Not useful",
				highLabel: "Very useful",
			}),
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 1 of 1");
		await expect(canvas.queryByRole("button", { name: "Next" })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Send answers" })).toBeVisible();
	},
};

/** A rating published without end labels still renders its scale, without an empty label line. */
export const RatingWithoutLabels: Story = {
	args: {
		questions: [question({ id: "useful", prompt: "How useful?", type: "RATING", required: true })],
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/[=]/)).toBeNull();
		await expect(canvas.getAllByRole("radio")).toHaveLength(5);
	},
};

export const LongPromptAndOptions: Story = {
	args: {
		questions: [
			question({
				id: "long",
				prompt:
					"When Hephaestus leaves practice feedback on a pull request that touches several packages at once, which of the following describes how you usually read it before deciding whether to act on it?",
				type: "SINGLE_CHOICE",
				options: [
					"I read the whole piece of feedback on the pull request page before opening any of the files it refers to",
					"I open the files first and come back to the feedback only when something in the diff surprises me",
					"Supercalifragilisticexpialidociouslylongunbrokenoptionwithoutanyspacesthatmustnotoverflow",
				],
				required: true,
			}),
		],
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const ManyQuestions: Story = {
	args: {
		questions: Array.from({ length: 20 }, (_, index) =>
			question({
				id: `q${index + 1}`,
				prompt: `Question ${index + 1}: how does this feel?`,
				type: "RATING",
				lowLabel: "Bad",
				highLabel: "Good",
			}),
		),
		draft: { answers: {}, item: "q12" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 12 of 20");
	},
};

/** While a submission is in flight the answers stay readable and checked; nothing can be pressed. */
export const Sending: Story = {
	args: {
		disabled: true,
		draft: {
			answers: { useful: "4", channel: "On the pull request", recommend: "9" },
			item: "improve",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("progressbar").closest("form")).toHaveAttribute(
			"aria-busy",
			"true",
		);
		for (const name of ["Previous", "Skip and send", "Send answers"]) {
			await expect(canvas.getByRole("button", { name })).toBeDisabled();
		}
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 4 of 4");
		const field = canvas.getByRole("textbox", { name: "Your answer" });
		await expect(field).not.toBeDisabled();
		field.focus();
		await expect(field).not.toHaveFocus();
	},
};

/** Skip clears the earlier pick as well, so going back shows the question as it was left: empty. */
export const SkipThenPreviousLeavesNothingChecked: Story = {
	args: { draft: { answers: { useful: "4" }, item: "channel" } },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: /On my practice page/ }));
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 3 of 4");
		await userEvent.click(canvas.getByRole("button", { name: "Previous" }));
		await expect(canvas.getByRole("radio", { name: /On my practice page/ })).not.toBeChecked();
		await expect(args.onDraftChange).toHaveBeenLastCalledWith({
			answers: { useful: "4", channel: undefined },
			item: "channel",
		});
	},
};

/** Skipping the last question sends the survey, so the button says so. */
export const SkipOnTheLastQuestionSends: Story = {
	args: {
		draft: {
			answers: { useful: "4", channel: "On the pull request", recommend: "7" },
			item: "improve",
		},
	},
	play: async ({ canvas, args }) => {
		await expect(canvas.queryByRole("button", { name: "Skip" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Skip and send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith([
			{ questionId: "useful", rating: 4 },
			{ questionId: "channel", choices: ["On the pull request"] },
			{ questionId: "recommend", rating: 7 },
		]);
	},
};

/** Sending from the last question with an earlier required question unanswered jumps back to it. */
export const SendJumpsBackToAnUnansweredRequiredQuestion: Story = {
	args: { draft: { answers: { channel: "On the pull request", recommend: "7" }, item: "improve" } },
	play: async ({ canvas, args }) => {
		await userEvent.type(canvas.getByRole("textbox", { name: "Your answer" }), "Less noise");
		await userEvent.click(canvas.getByRole("button", { name: "Send answers" }));
		await expect(args.onSubmit).not.toHaveBeenCalled();
		await expect(canvas.getByRole("progressbar")).toHaveTextContent("Question 1 of 4");
		await expect(canvas.getByRole("alert")).toHaveTextContent("Choose an answer to continue.");
		await expect(args.onDraftChange).toHaveBeenLastCalledWith(
			expect.objectContaining({ item: "useful" }),
		);
	},
};

export const DarkScale: Story = {
	args: { draft: { answers: { useful: "4" } } },
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: "4" })).toBeChecked();
	},
};
