import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { ADA, GRACE, WAYFINDING } from "./fixtures";
import { TeamProjectCard } from "./TeamProjectCard";

const meta = {
	component: TeamProjectCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		team: { ...WAYFINDING, members: [ADA] },
		candidates: [GRACE],
		onAddMember: fn(),
		onRemoveMember: fn(),
	},
} satisfies Meta<typeof TeamProjectCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing is chosen yet, so Add to team adds no one. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Add to team" })).toBeDisabled();
	},
};

/** Tab to the control and type a letter, with the list closed: nobody joins the team (review of #5465). */
export const TypingOnTheClosedControlAddsNobody: Story = {
	play: async ({ args, canvas }) => {
		const control = canvas.getByRole("combobox", {
			name: "Choose a member to add to Team Wayfinding",
		});
		await userEvent.tab();
		await userEvent.tab();
		await expect(control).toHaveFocus();
		await userEvent.keyboard("g");
		await expect(control).toHaveAttribute("aria-expanded", "false");
		await expect(args.onAddMember).not.toHaveBeenCalled();
	},
};

/** While an add or a removal is in flight, nothing starts a second add (review of #5465). */
export const PendingAcceptsNoSecondAdd: Story = {
	args: { pending: true },
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("combobox", { name: "Choose a member to add to Team Wayfinding" }),
		);
		const option = screen.queryByRole("option", { name: "Grace Hopper (ghopper)" });
		if (option !== null) {
			await userEvent.click(option);
		}
		await expect(args.onAddMember).not.toHaveBeenCalled();
	},
};
