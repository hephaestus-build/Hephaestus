import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { ALAN, WAYFINDING } from "./fixtures";
import { RemoveTeamMemberDialog } from "./RemoveTeamMemberDialog";

const meta = {
	component: RemoveTeamMemberDialog,
	tags: ["autodocs"],
	args: { removal: { team: WAYFINDING, member: ALAN }, onConfirm: fn(), onClose: fn() },
} satisfies Meta<typeof RemoveTeamMemberDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The confirmation says what the removal ends at once and what it withdraws. */
export const Default: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog", {
			name: "Remove Alan Turing from Team Wayfinding?",
		});
		await expect(dialog).toHaveTextContent(/at once/u);
		await expect(dialog).toHaveTextContent(/versions they sent to this team are withdrawn/u);
		await userEvent.click(screen.getByRole("button", { name: "Remove from team" }));
		await expect(args.onConfirm).toHaveBeenCalledWith({ team: WAYFINDING, member: ALAN });
	},
};

export const Closed: Story = {
	args: { removal: null },
	play: async () => {
		await expect(screen.queryByRole("alertdialog")).toBeNull();
	},
};
