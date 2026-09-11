import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/test/overlay";

import { researchInvitation, surveyInvitation } from "./product-survey-fixtures";
import { ProductFeedbackMenu } from "./ProductFeedbackMenu";

const meta = {
	title: "Product feedback/Header menu",
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
		await userEvent.click(canvas.getByRole("button", { name: "Feedback, 1 survey waiting" }));
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
		await expectSettledVisible(menu.getByRole("menuitem", { name: "Share an idea" }));
		// No survey, no survey section: an empty list in a menu is noise.
		await expect(menu.queryByText(/Surveys/)).toBeNull();
		await expect(menu.getByRole("menuitem", { name: /Open an issue on GitHub/ })).toHaveAttribute(
			"href",
			"https://github.com/hephaestus-build/Hephaestus/issues/new/choose",
		);
		await userEvent.click(menu.getByRole("menuitem", { name: "Report a bug" }));
		await expect(args.onSendFeedback).toHaveBeenCalledWith("BUG");
	},
};

/** A research invitation says so in the list, before anything opens. */
export const WithResearchInvitation: Story = {
	args: { invitations: [surveyInvitation, researchInvitation] },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Feedback, 2 surveys waiting" }));
		const menu = within(await screen.findByRole("menu"));
		await expectSettledVisible(menu.getByText(/Research · 3 questions · about 2 minutes/));
	},
};

/** The trigger's name counts the invitations, so a screen reader hears how many are waiting. */
export const SeveralSurveys: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Feedback, 2 surveys waiting" })).toBeVisible();
	},
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
