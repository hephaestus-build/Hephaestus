import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectSettledVisible } from "@/test/overlay";

import { surveyInvitation } from "./product-survey-fixtures";
import { ProductSurveyDialog } from "./ProductSurveyDialog";

const meta = {
	title: "Surveys/Product survey dialog",
	component: ProductSurveyDialog,
	args: {
		survey: surveyInvitation,
		open: true,
		onOpenChange: fn(),
		draft: {},
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

/** The estimate and the closing time sit under the purpose, so the ask is honest before the first question. */
export const Default: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByText(/4 questions · about 2 minutes/));
		await expect(dialog.getByRole("button", { name: "Send answers" })).toBeDisabled();
	},
};

/** Sending is gated on the required questions only; the optional ones can stay empty. */
export const AnswersTheRequiredQuestionAndSends: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		const useful = within(dialog.getByRole("radiogroup", { name: /How useful/ }));
		await userEvent.click(useful.getByRole("radio", { name: "5" }));
		await userEvent.type(
			dialog.getByRole("textbox", { name: /What would make it more useful/ }),
			"Less noise ",
		);
		await userEvent.click(dialog.getByRole("button", { name: "Send answers" }));
		await expect(args.onSubmit).toHaveBeenCalledWith([
			{ questionId: "useful", rating: 5 },
			{ questionId: "improve", text: "Less noise" },
		]);
	},
};

export const Sending: Story = {
	args: { isSubmitting: true, draft: { useful: 4 } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("button", { name: "Sending…" }));
		await expect(dialog.getByRole("button", { name: "Decline survey" })).toBeDisabled();
	},
};

export const Error: Story = {
	args: { draft: { useful: 4 }, error: "Couldn't send. Your draft is still here." },
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
