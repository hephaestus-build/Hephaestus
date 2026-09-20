import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import { SlackPreferencesSection } from "./SlackPreferencesSection";

const meta = {
	component: SlackPreferencesSection,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		onConnectSlack: fn(),
		onToggleChannelMessages: fn(),
		onRetry: fn(),
	},
} satisfies Meta<typeof SlackPreferencesSection>;

export default meta;
type Story = StoryObj<typeof meta>;

const workspace = {
	workspaceSlug: "hephaestustest",
	workspaceName: "Hephaestus Test",
	slackTeamId: "T1",
	slackTeamName: "hephaestus-test",
	slackUserId: "U1",
	slackDisplayName: "Felix",
	channelMessagesAllowed: true,
	activeMonitoredChannelCount: 2,
};

export const Linked: Story = {
	args: {
		workspaces: [workspace],
		isSlackLinked: true,
		canConnectSlack: true,
	},
};

/**
 * Turning message use OFF deletes already-collected channel messages, so the switch cannot do it
 * alone — the OFF transition is gated by a destructive confirmation. Turning it ON stays instant.
 */
export const ConfirmTurningOff: Story = {
	args: {
		workspaces: [workspace],
		isSlackLinked: true,
		canConnectSlack: true,
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("switch", { name: /use my new channel messages/iu }));
		// Flipping the switch alone must not delete anything.
		await expect(args.onToggleChannelMessages).not.toHaveBeenCalled();

		const confirm = await screen.findByRole("button", { name: /turn off & delete/iu });
		await userEvent.click(confirm);
		await expect(args.onToggleChannelMessages).toHaveBeenCalledWith("hephaestustest", false);
	},
};

/** Message use already off — switching it back ON is not destructive, so it fires immediately. */
export const MessageUseOff: Story = {
	args: {
		workspaces: [{ ...workspace, channelMessagesAllowed: false }],
		isSlackLinked: true,
		canConnectSlack: true,
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("switch", { name: /use my new channel messages/iu }));
		await expect(args.onToggleChannelMessages).toHaveBeenCalledWith("hephaestustest", true);
	},
};

export const NotLinked: Story = {
	args: {
		workspaces: [],
		isSlackLinked: false,
		canConnectSlack: true,
	},
};

export const ConnectedWithoutWorkspace: Story = {
	args: {
		workspaces: [],
		isSlackLinked: true,
		canConnectSlack: true,
	},
};

export const Loading: Story = {
	args: {
		workspaces: [],
		isSlackLinked: true,
		canConnectSlack: true,
		isLoading: true,
	},
};

/** Failed load → the shared error alert, with a retry. */
export const ErrorState: Story = {
	args: {
		workspaces: [],
		isSlackLinked: true,
		canConnectSlack: true,
		isError: true,
		error: { detail: "Slack is not responding." },
	},
	play: async ({ args, canvas }) => {
		canvas.getByText(/could not load your slack preferences/iu);
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};
