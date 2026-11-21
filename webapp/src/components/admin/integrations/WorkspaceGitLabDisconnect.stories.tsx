import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { expectDismissed, expectSettledVisible } from "@/stories/overlay";
import { expectGenuinelyDisabled } from "@/test/controls";

import { WorkspaceGitLabDisconnect } from "./WorkspaceGitLabDisconnect";

const onDisconnect = fn(async () => undefined);
const onRejectedDisconnect = fn(async () => {
	throw new Error("GitLab did not answer");
});

const meta = {
	component: WorkspaceGitLabDisconnect,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { onDisconnect, isDisconnecting: false },
} satisfies Meta<typeof WorkspaceGitLabDisconnect>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect GitLab…" }));
		const dialog = await screen.findByRole("alertdialog", { name: "Disconnect GitLab?" });
		await expectSettledVisible(dialog);
		await expect(onDisconnect).not.toHaveBeenCalled();
		await userEvent.click(within(dialog).getByRole("button", { name: "Disconnect GitLab" }));
		await expect(onDisconnect).toHaveBeenCalledOnce();
		await expectDismissed("alertdialog");
	},
};

export const Disconnecting: Story = {
	args: { isDisconnecting: true },
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Disconnecting…" }));
	},
};

/** A disconnect that fails keeps the dialog open, so the admin can try again from where they were. */
export const DisconnectRejected: Story = {
	args: { onDisconnect: onRejectedDisconnect },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect GitLab…" }));
		const dialog = await screen.findByRole("alertdialog", { name: "Disconnect GitLab?" });
		await expectSettledVisible(dialog);
		await userEvent.click(within(dialog).getByRole("button", { name: "Disconnect GitLab" }));
		await expect(onRejectedDisconnect).toHaveBeenCalledOnce();
		await expect(screen.getByRole("alertdialog", { name: "Disconnect GitLab?" })).toBeVisible();
	},
};
