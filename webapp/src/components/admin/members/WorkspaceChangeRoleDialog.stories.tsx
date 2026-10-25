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
	args: { change: makeOwner, isPending: false, onOpenChange: fn(), onConfirm: fn() },
} satisfies Meta<typeof WorkspaceChangeRoleDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Making someone an owner: the reader learns that the new owner can delete the workspace. */
export const MakeOwner: Story = {
	play: async ({ args }) => {
		const dialog = within(
			await screen.findByRole("alertdialog", { name: "Make Ada Lovelace an owner?" }),
		);
		await expectSettledVisible(
			dialog.getByText(
				"Ada Lovelace can then delete this workspace and change the role of anyone in it, including you.",
			),
		);
		await expect(args.onConfirm).not.toHaveBeenCalled();
		await userEvent.click(dialog.getByRole("button", { name: "Make owner" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(makeOwner);
	},
};

/** Lowering your own role: Cancel keeps it and commits nothing. */
export const RemoveOwnAdminAccess: Story = {
	args: { change: ownDemotion },
	play: async ({ args }) => {
		const dialog = within(
			await screen.findByRole("alertdialog", { name: "Remove your admin access?" }),
		);
		await expectSettledVisible(
			dialog.getByText(/Only an admin can give you admin access again\./u),
		);
		await userEvent.click(dialog.getByRole("button", { name: "Cancel" }));
		await expect(args.onOpenChange).toHaveBeenCalledWith(false);
		await expect(args.onConfirm).not.toHaveBeenCalled();
	},
};

/** An owner who becomes an admin keeps admin access, so the dialog names only what they lose. */
export const GiveUpOwnOwnerRole: Story = {
	args: { change: { ...ownDemotion, from: "OWNER", to: "ADMIN" } },
	play: async () => {
		const dialog = within(
			await screen.findByRole("alertdialog", { name: "Give up your owner role?" }),
		);
		await expectSettledVisible(dialog.getByText(/You stay an admin/u));
		await expect(dialog.getByRole("button", { name: "Give up owner role" })).toBeEnabled();
	},
};

/** In flight: both buttons wait, so the change is not sent twice and the dialog cannot close under it. */
export const Pending: Story = {
	args: { isPending: true },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(dialog.getByRole("button", { name: "Make owner" })).toBeDisabled();
		await expect(dialog.getByRole("button", { name: "Cancel" })).toBeDisabled();
		await userEvent.keyboard("{Escape}");
		await expect(args.onOpenChange).not.toHaveBeenCalled();
	},
};

/** The server refused: the reason stays in the dialog so the reader can read it. */
export const Refused: Story = {
	args: { errorMessage: "You cannot manage this role in this workspace." },
	play: async () => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(dialog.getByRole("alert")).toHaveTextContent(
			"You cannot manage this role in this workspace.",
		);
		await expect(dialog.getByRole("button", { name: "Make owner" })).toBeEnabled();
	},
};
