import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { unclaimedMember } from "./fixtures";
import { workspaceMemberRows } from "./workspace-members";
import { WorkspaceRemoveMemberDialog } from "./WorkspaceRemoveMemberDialog";

const [bob] = workspaceMemberRows([unclaimedMember]);

/**
 * Asks before a member leaves the workspace. The member keeps a row that says when they left and
 * offers Add back, so the dialog says how to undo the removal.
 */
const meta = {
	component: WorkspaceRemoveMemberDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { member: bob ?? null, onConfirm: fn(), onClose: fn() },
} satisfies Meta<typeof WorkspaceRemoveMemberDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The dialog names the member and says what they lose, what stays, and how to undo it. */
export const Confirming: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog", {
			name: "Remove Bob Smith from this workspace?",
		});
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveAccessibleDescription(
			"Bob Smith loses access to this workspace, and Hephaestus stops sending them practice feedback here. Their history stays recorded, and you can add them back on this page.",
		);
		await expect(args.onConfirm).not.toHaveBeenCalled();
	},
};

/** Where GitHub or GitLab adds members, the dialog warns that the next sync adds back a person it still lists. */
export const MembersFromAProvider: Story = {
	args: { providerLabel: "GitHub" },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog");
		await expect(dialog).toHaveAccessibleDescription(
			/If they are still a member in GitHub, the next sync adds them back\.$/u,
		);
	},
};

/** Confirming hands the member back and closes at once; the row reports the outcome. */
export const ConfirmCommitsAndCloses: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Remove from workspace" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(bob);
		await expect(args.onClose).toHaveBeenCalledTimes(1);
	},
};

/** Cancel removes nobody. */
export const CancelCommitsNothing: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Cancel" }));
		await expect(args.onClose).toHaveBeenCalled();
		await expect(args.onConfirm).not.toHaveBeenCalled();
	},
};
