import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/test/overlay";

import { surveyInvitation } from "./product-survey-fixtures";
import { ProductFeedbackMenu } from "./ProductFeedbackMenu";

const meta = {
	title: "Surveys/Product feedback menu",
	component: ProductFeedbackMenu,
	args: { invitations: [surveyInvitation], onSendFeedback: fn(), onOpenSurvey: fn() },
	decorators: [(Story) => <div className="flex justify-end p-4">{<Story />}</div>],
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackMenu>;
export default meta;
type Story = StoryObj<typeof meta>;

/** The count on the trigger is the only signal; nothing opens until the member chooses. */
export const WithInvitation: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Feedback, 1 open survey" }));
		const menu = within(await screen.findByRole("menu"));
		await expectSettledVisible(menu.getByText(/4 questions · about 2 minutes/));
		await userEvent.click(menu.getByRole("menuitem", { name: /Help improve practice feedback/ }));
		await expect(args.onOpenSurvey).toHaveBeenCalledWith(surveyInvitation.id);
	},
};

export const NoSurveys: Story = {
	args: { invitations: [] },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Feedback" }));
		const menu = within(await screen.findByRole("menu"));
		await expectSettledVisible(menu.getByText("No open surveys."));
		await userEvent.click(menu.getByRole("menuitem", { name: "Report a bug" }));
		await expect(args.onSendFeedback).toHaveBeenCalledWith("BUG");
	},
};

export const SeveralSurveys: Story = {
	args: {
		invitations: [
			surveyInvitation,
			{
				...surveyInvitation,
				id: "2",
				title: "Onboarding check-in",
				questions: surveyInvitation.questions.slice(0, 1),
			},
		],
	},
};
