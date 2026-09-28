import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { expectDismissed, expectSettledVisible } from "@/stories/overlay";
import { expectGenuinelyDisabled } from "@/test/controls";

import { WorkspaceSlackConnectionSettings } from "./WorkspaceSlackConnectionSettings";

const onDisconnect = fn(async () => undefined);
const onRejectedDisconnect = fn(async () => {
	throw new Error("Slack did not answer");
});

const meta = {
	component: WorkspaceSlackConnectionSettings,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { state: "connected", onDisconnect, isDisconnecting: false },
} satisfies Meta<typeof WorkspaceSlackConnectionSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: { state: "connected", onDisconnect, isDisconnecting: false },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect Slack…" }));
		const dialog = await screen.findByRole("alertdialog", { name: "Disconnect Slack?" });
		await expectSettledVisible(dialog);
		await expect(onDisconnect).not.toHaveBeenCalled();
		await userEvent.click(within(dialog).getByRole("button", { name: "Disconnect" }));
		await expect(onDisconnect).toHaveBeenCalledOnce();
		await expectDismissed("alertdialog");
	},
};

/** A disconnect that fails keeps the dialog open, so the admin can try again from where they were. */
export const DisconnectRejected: Story = {
	args: { state: "connected", onDisconnect: onRejectedDisconnect, isDisconnecting: false },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect Slack…" }));
		const dialog = await screen.findByRole("alertdialog", { name: "Disconnect Slack?" });
		await expectSettledVisible(dialog);
		await userEvent.click(within(dialog).getByRole("button", { name: "Disconnect" }));
		await expect(onRejectedDisconnect).toHaveBeenCalledOnce();
		await expect(screen.getByRole("alertdialog", { name: "Disconnect Slack?" })).toBeVisible();
	},
};

/** The server has not named the connection, so there is nothing a disconnect could act on. */
export const WithoutConnectionId: Story = {
	args: { state: "connected", onDisconnect: undefined, isDisconnecting: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /Disconnect/u })).not.toBeInTheDocument();
	},
};

export const NotConnected: Story = {
	args: { state: "disconnected", onConnect: fn(), isConnecting: false },
};

export const Connecting: Story = {
	args: { state: "disconnected", onConnect: fn(), isConnecting: true },
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Redirecting to Slack…" }));
	},
};

export const CredentialUnreadable: Story = {
	args: {
		state: "connected",
		credentialsUnreadableSince: new Date("2026-09-20T08:00:00Z"),
		onDisconnect,
		isDisconnecting: false,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Token unreadable")).toBeVisible();
		await expect(canvas.queryByText("Connected")).not.toBeInTheDocument();
	},
};
