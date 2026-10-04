import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { expectDismissed, expectSettledVisible } from "@/stories/overlay";
import { expectGenuinelyDisabled } from "@/test/controls";

import { WorkspaceSlackConnectionSettings } from "./WorkspaceSlackConnectionSettings";

const onReconnect = fn();
const onDisconnect = fn(async () => undefined);
const onRejectedDisconnect = fn(async () => {
	throw new Error("Slack did not answer");
});

const connected = {
	state: "connected",
	onReconnect,
	isReconnecting: false,
	onDisconnect,
	isDisconnecting: false,
} as const;

const meta = {
	component: WorkspaceSlackConnectionSettings,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: connected,
} satisfies Meta<typeof WorkspaceSlackConnectionSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: connected,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect Slack…" }));
		const dialog = await screen.findByRole("alertdialog", { name: "Disconnect Slack?" });
		await expectSettledVisible(dialog);
		await expect(onDisconnect).not.toHaveBeenCalled();
		await userEvent.click(within(dialog).getByRole("button", { name: "Disconnect Slack" }));
		await expect(onDisconnect).toHaveBeenCalledOnce();
		await expectDismissed("alertdialog");
	},
};

/** A disconnect that fails keeps the dialog open, so the admin can try again from where they were. */
export const DisconnectRejected: Story = {
	args: { ...connected, onDisconnect: onRejectedDisconnect },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect Slack…" }));
		const dialog = await screen.findByRole("alertdialog", { name: "Disconnect Slack?" });
		await expectSettledVisible(dialog);
		await userEvent.click(within(dialog).getByRole("button", { name: "Disconnect Slack" }));
		await expect(onRejectedDisconnect).toHaveBeenCalledOnce();
		await expect(screen.getByRole("alertdialog", { name: "Disconnect Slack?" })).toBeVisible();
	},
};

/** The server has not named the connection, so there is nothing a disconnect could act on. */
export const WithoutConnectionId: Story = {
	args: { ...connected, onDisconnect: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /Disconnect/u })).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Reconnect Slack" })).toBeEnabled();
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

/** Reconnecting replaces the token in place, so it asks for no confirmation and erases nothing. */
export const CredentialUnreadable: Story = {
	args: { ...connected, credentialsUnreadableSince: new Date("2026-09-20T08:00:00Z") },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByText("Token unreadable")).toBeVisible();
		await expect(canvas.queryByText("Connected")).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "Reconnect Slack" }));
		await expect(onReconnect).toHaveBeenCalledOnce();
		await expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
		await expect(onDisconnect).not.toHaveBeenCalled();
	},
};

/** While the page leaves for Slack, neither action can start a second request. */
export const Reconnecting: Story = {
	args: {
		...connected,
		credentialsUnreadableSince: new Date("2026-09-20T08:00:00Z"),
		isReconnecting: true,
	},
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Redirecting to Slack…" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Disconnect Slack…" }));
	},
};
