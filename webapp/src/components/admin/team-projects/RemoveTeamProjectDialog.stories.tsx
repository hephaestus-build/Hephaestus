import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { WAYFINDING } from "./fixtures";
import { RemoveTeamProjectDialog } from "./RemoveTeamProjectDialog";

const meta = {
	component: RemoveTeamProjectDialog,
	tags: ["autodocs"],
	args: { team: WAYFINDING, onConfirm: fn(), onClose: fn() },
} satisfies Meta<typeof RemoveTeamProjectDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The confirmation says what ends at once, what is withdrawn, and that it cannot be undone. */
export const Default: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog", { name: "Remove Team Wayfinding?" });
		await expect(dialog).toHaveTextContent(/All 2 members lose access .* at once\./u);
		await expect(dialog).toHaveTextContent(
			/Every version the team sent is withdrawn, and its content is deleted\./u,
		);
		await expect(dialog).toHaveTextContent(/You cannot undo this\./u);
		await userEvent.click(screen.getByRole("button", { name: "Remove team project" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(WAYFINDING);
	},
};

export const Closed: Story = {
	args: { team: null },
	play: async () => {
		await expect(screen.queryByRole("alertdialog")).toBeNull();
	},
};
