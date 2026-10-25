import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import type { WorkspaceRoleChange } from "./workspace-members";
import { WorkspaceChangeRoleDialog } from "./WorkspaceChangeRoleDialog";

const makeOwner: WorkspaceRoleChange = {
	accountId: "11",
	name: "Ada Lovelace",
	from: "ADMIN",
	to: "OWNER",
	own: false,
};
const ownDemotion: WorkspaceRoleChange = {
	accountId: "42",
	name: "The Octocat",
	from: "ADMIN",
	to: "MEMBER",
	own: true,
};

/**
 * Asks before a workspace role change that is hard to take back. Other role changes save in one
 * step and never open it.
 */
const meta = {
	component: WorkspaceChangeRoleDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { change: makeOwner, onConfirm: fn(), onClose: fn() },
} satisfies Meta<typeof WorkspaceChangeRoleDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Making someone an owner: the reader learns that the new owner can delete the workspace. */
export const MakeOwner: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog", { name: "Make Ada Lovelace an owner?" });
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveAccessibleDescription(
			"Ada Lovelace can then delete this workspace and change the role of anyone in it, including you.",
		);
		await expect(args.onConfirm).not.toHaveBeenCalled();
	},
};

/** Confirming hands the change back and closes at once; the row reports the outcome. */
export const ConfirmCommitsAndCloses: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Make owner" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(makeOwner);
		await expect(args.onClose).toHaveBeenCalledTimes(1);
	},
};

/** Lowering your own role deletes nothing, so its confirm uses the default style, not the destructive one. */
export const RemoveOwnAdminAccess: Story = {
	args: { change: ownDemotion },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog", { name: "Remove your admin access?" });
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveAccessibleDescription(
			"You become a member and can no longer manage this workspace. An admin or owner can give you admin access again.",
		);
		await expect(within(dialog).getByRole("button", { name: "Remove admin access" })).toBeEnabled();
	},
};

/** Cancel commits nothing. */
export const CancelCommitsNothing: Story = {
	args: { change: ownDemotion },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Cancel" }));
		await expect(args.onClose).toHaveBeenCalled();
		await expect(args.onConfirm).not.toHaveBeenCalled();
	},
};

/** An owner who becomes an admin keeps admin access, so the dialog names only what they lose. */
export const GiveUpOwnOwnerRole: Story = {
	args: { change: { ...ownDemotion, from: "OWNER", to: "ADMIN" } },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog", { name: "Give up your owner role?" });
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveAccessibleDescription(/^You stay an admin/u);
		await expect(within(dialog).getByRole("button", { name: "Give up owner role" })).toBeEnabled();
	},
};

/** An owner who becomes a member is told that only an owner can restore the owner role. */
export const OwnerBecomesMember: Story = {
	args: { change: { ...ownDemotion, from: "OWNER" } },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog", { name: "Remove your admin access?" });
		await expect(dialog).toHaveAccessibleDescription(
			/Only an owner can make you an owner again\.$/u,
		);
	},
};
