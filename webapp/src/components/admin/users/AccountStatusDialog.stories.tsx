import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { AdminAccountView } from "@/api/types.gen";

import { AccountStatusDialog } from "./AccountStatusDialog";

const active: AdminAccountView = {
	id: 2,
	displayName: "Bob User",
	primaryEmail: "bob@example.com",
	appRole: "USER",
	status: "ACTIVE",
};
const blocked: AdminAccountView = {
	...active,
	id: 3,
	displayName: "Carol Blocked",
	status: "BLOCKED",
};

/** Confirms blocking or unblocking an account in the instance users table. */
const meta = {
	component: AccountStatusDialog,
	tags: ["autodocs"],
	args: { user: active, open: true, pending: false, onOpenChange: fn(), onConfirm: fn() },
} satisfies Meta<typeof AccountStatusDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Block: Story = {
	play: async ({ args }) => {
		await screen.findByRole("alertdialog", { name: "Block Bob User?" });
		await userEvent.click(screen.getByRole("button", { name: "Block account" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(active);
	},
};

export const Unblock: Story = {
	args: { user: blocked },
	play: async ({ args }) => {
		await screen.findByRole("alertdialog", { name: "Unblock Carol Blocked?" });
		await userEvent.click(screen.getByRole("button", { name: "Unblock account" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(blocked);
	},
};

/** While the change saves, the action waits and says so. */
export const Saving: Story = {
	args: { pending: true },
	play: async () => {
		await expect(await screen.findByRole("button", { name: "Blocking…" })).toBeDisabled();
	},
};

/** An account without a display name is named generically. */
export const NoName: Story = {
	args: { user: { ...active, displayName: undefined } },
	play: async () => {
		await screen.findByRole("alertdialog", { name: "Block this account?" });
	},
};
