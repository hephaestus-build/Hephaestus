import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectSettledVisible } from "@/test/overlay";

import { surveyInvitation } from "./product-survey-fixtures";
import { ProductSurveyDialog } from "./ProductSurveyDialog";
import { EMPTY_SURVEY_RESPONSE_DRAFT } from "./survey-questions";

const meta = {
	title: "Product feedback/Survey dialog",
	component: ProductSurveyDialog,
	args: {
		survey: surveyInvitation,
		open: true,
		onOpenChange: fn(),
		draft: EMPTY_SURVEY_RESPONSE_DRAFT,
		onDraftChange: fn(),
		isSubmitting: false,
		onSubmit: fn(),
		onDecline: fn(),
	},
	render: (args) => (
		<Stateful initial={args.draft}>
			{(draft, setDraft) => (
				<ProductSurveyDialog
					{...args}
					draft={draft}
					onDraftChange={(next) => {
						args.onDraftChange(next);
						setDraft(next);
					}}
				/>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ProductSurveyDialog>;
export default meta;
type Story = StoryObj<typeof meta>;

/** The ask is stated before the first question: how many, how long, and when it closes. */
export const Default: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByText(/4 questions · about 2 minutes/));
		await expect(dialog.getByText(/Closes in 6 days/)).toBeVisible();
		await expect(dialog.getByRole("progressbar")).toHaveTextContent("Question 1 of 4");
		await expect(dialog.getByRole("button", { name: "Decline survey" })).toBeVisible();
	},
};

/** Opening lands on the first answer, not on the scroll region in front of it. */
export const FocusesTheFirstAnswer: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("progressbar"));
		await expect(dialog.getByRole("radio", { name: "1" })).toHaveFocus();
	},
};

/** A skipped question is left out of the send. */
export const SkipsAnOptionalQuestionAndSends: Story = {
	args: {
		draft: { answers: { useful: "5", channel: "On the pull request" }, item: "recommend" },
	},
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Skip" }));
		await userEvent.type(dialog.getByRole("textbox", { name: "Your answer" }), "Less noise ");
		await userEvent.click(dialog.getByRole("button", { name: "Send answers" }));
		await expect(args.onSubmit).toHaveBeenCalledWith([
			{ questionId: "useful", rating: 5 },
			{ questionId: "channel", choices: ["On the pull request"] },
			{ questionId: "improve", text: "Less noise" },
		]);
	},
};

export const ResumesADraft: Story = {
	args: { draft: { answers: { useful: "2" }, item: "channel" } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("progressbar"));
		await expect(dialog.getByRole("progressbar")).toHaveTextContent("Question 2 of 4");
	},
};

export const Sending: Story = {
	args: { isSubmitting: true, draft: { answers: { useful: "4" }, item: "improve" } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("button", { name: "Sending…" }));
		await expect(dialog.getByRole("button", { name: "Sending…" })).toBeDisabled();
		await expect(dialog.getByRole("button", { name: "Decline survey" })).toBeDisabled();
	},
};

export const Error: Story = {
	args: {
		draft: { answers: { useful: "4" }, item: "improve" },
		error: "Couldn't send. Your draft is still here.",
	},
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("button", { name: "Send answers" }));
		await expect(dialog.getByRole("alert")).toHaveTextContent("Your draft is still here");
	},
};

export const Declines: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Decline survey" }));
		await expect(args.onDecline).toHaveBeenCalledOnce();
	},
};

export const NoClosingDate: Story = {
	args: { survey: { ...surveyInvitation, endsAt: undefined } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByText(/4 questions · about 2 minutes/));
		await expect(dialog.queryByText(/Closes/)).toBeNull();
	},
};

export const Mobile: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	args: { draft: { answers: {}, item: "recommend" } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("progressbar"));
		await expect(dialog.getByRole("progressbar")).toHaveTextContent("Question 3 of 4");
	},
};
