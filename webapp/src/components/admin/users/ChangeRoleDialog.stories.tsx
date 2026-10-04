import type { Meta, StoryObj } from "@storybook/react-vite";
import { ShieldCheck, ShieldOff } from "lucide-react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { AdminAccountView } from "@/api/types.gen";

import { ChangeRoleDialog } from "./ChangeRoleDialog";

const regularUser: AdminAccountView = {
	id: 2,
	displayName: "Bob User",
	primaryEmail: "bob@example.com",
	appRole: "USER",
	status: "ACTIVE",
};
const adminUser: AdminAccountView = {
	...regularUser,
	id: 3,
	displayName: "Ada Admin",
	appRole: "APP_ADMIN",
};

/**
 * Confirms toggling a single account between User and Instance admin. Granting is an elevation, so it is
 * surfaced as a destructive-styled confirmation; revoking is neutral.
 */
const meta = {
	component: ChangeRoleDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { onOpenChange: fn(), onConfirm: fn(), isPending: false },
} satisfies Meta<typeof ChangeRoleDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Granting Instance admin — destructive-styled "Grant instance admin" action; confirming reports the next role. */
export const GrantAdmin: Story = {
	args: { user: regularUser, icon: ShieldCheck },
	play: async ({ args }) => {
		await screen.findByRole("alertdialog");
		screen.getByText("Grant instance admin?");
		await expect(screen.getByRole("alertdialog")).toHaveTextContent(
			/gets the Instance admin role/u,
		);
		await userEvent.click(screen.getByRole("button", { name: "Grant instance admin" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(regularUser, "APP_ADMIN");
	},
};

/** Revoking Instance admin — neutral confirmation; confirming downgrades to USER. */
export const RevokeAdmin: Story = {
	args: { user: adminUser, icon: ShieldOff },
	play: async ({ args }) => {
		await screen.findByRole("alertdialog");
		await userEvent.click(screen.getByRole("button", { name: "Revoke instance admin" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(adminUser, "USER");
	},
};

/** In flight — the confirm action is disabled to block a double-submit. */
export const Pending: Story = {
	args: { user: regularUser, icon: ShieldCheck, isPending: true },
	play: async () => {
		await screen.findByRole("alertdialog");
		await expect(screen.getByRole("button", { name: /grant instance admin/iu })).toBeDisabled();
	},
};
