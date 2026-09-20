import type { Meta, StoryObj } from "@storybook/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { WorkspaceSlackNotificationSettings } from "./WorkspaceSlackNotificationSettings";

const meta = {
	component: WorkspaceSlackNotificationSettings,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<QueryClientProvider client={new QueryClient()}>
				<Story />
			</QueryClientProvider>
		),
	],
	args: {
		workspaceSlug: "demo-workspace",
		hasSlackConnection: false,
		enabled: false,
		scheduleDay: 1,
		scheduleTime: "09:00",
		channelCandidates: [],
		onSaved: fn(),
	},
} satisfies Meta<typeof WorkspaceSlackNotificationSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Cold start — admin hasn't connected the workspace yet. */
export const NotConnected: Story = {
	args: { hasSlackConnection: false },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: /connect slack workspace/iu });
	},
};

/** OAuth completed, but no digest channel is selected yet. */
export const ConnectedNoChannel: Story = {
	args: { hasSlackConnection: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /^save$/iu })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: /send test message/iu })).toBeDisabled();
	},
};

/** Fully configured: connected, channel + team filter set, digest enabled. */
export const ConnectedConfigured: Story = {
	args: {
		hasSlackConnection: true,
		channelId: "C0974LJBPBK",
		teamLabel: "engineering",
		enabled: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /^save$/iu })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: /send test message/iu })).toBeEnabled();
	},
};

/** Toggling the digest Switch off updates local form state. */
export const ToggleDigestOff: Story = {
	args: {
		hasSlackConnection: true,
		channelId: "C0974LJBPBK",
		teamLabel: "engineering",
		enabled: true,
	},
	play: async ({ canvas }) => {
		const digest = canvas.getByRole("switch", { name: /send weekly digest/iu });
		await expect(digest).toBeChecked();
		await userEvent.click(digest);
		await expect(digest).not.toBeChecked();
	},
};

/**
 * One control for one value: the digest channel is chosen from a combobox that shows
 * `#channel-name`. The stable Slack id is held in state and is never dumped into a visible text
 * box. Disabled options carry a reason instead of vanishing from the list.
 */
export const WithChannelPicker: Story = {
	args: {
		hasSlackConnection: true,
		channelCandidates: [
			{
				slackChannelId: "C01GENERAL01",
				channelName: "general",
				privateChannel: false,
				member: true,
				archived: false,
			},
			{
				slackChannelId: "C02PRIVATE02",
				channelName: "private-team",
				privateChannel: true,
				member: false,
				archived: false,
			},
		],
	},
	play: async ({ canvas }) => {
		// The options live in a portalled popover — open the combobox, then query the document.
		// Scope by name: the schedule Day <Select> is also exposed as role="combobox".
		const trigger = canvas.getByRole("combobox", { name: /digest channel/iu });
		await userEvent.click(trigger);

		await expect(await screen.findByRole("option", { name: /#private-team/iu })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		screen.getByText(/needs invite/iu);

		// Search narrows the option list to the match.
		await userEvent.type(
			screen.getByRole("combobox", { name: /search digest slack channels/iu }),
			"gen",
		);
		screen.getByRole("option", { name: /#general/iu });
		await expect(screen.queryByRole("option", { name: /#private-team/iu })).not.toBeInTheDocument();

		await userEvent.click(screen.getByRole("option", { name: /#general/iu }));
		await expect(trigger).toHaveTextContent("#general");
		await expect(canvas.queryByDisplayValue("C01GENERAL01")).not.toBeInTheDocument();
	},
};

/** A channel Slack never listed is still reachable — the paste escape hatch resolves a link. */
export const PasteChannelLink: Story = {
	args: { hasSlackConnection: true },
	play: async ({ canvas }) => {
		// With no candidates the paste path is the only path, so it is already open.
		await userEvent.type(
			canvas.getByLabelText(/paste a channel link or id/iu),
			"https://acme.slack.com/archives/C0974LJBPBK",
		);
		await expect(canvas.getByRole("button", { name: /send test message/iu })).toBeEnabled();
	},
};

/** Non-default day — the day Select renders the label ("Thursday"), not the raw value. */
export const NonDefaultDay: Story = {
	args: {
		hasSlackConnection: true,
		channelId: "C0974LJBPBK",
		enabled: true,
		scheduleDay: 4,
		scheduleTime: "14:30",
	},
	play: async ({ canvas }) => {
		canvas.getByText("Thursday");
	},
};

/** Invalid channel id — the field shows its format error and Save is disabled. */
export const InvalidChannel: Story = {
	args: {
		hasSlackConnection: true,
		channelId: "not-a-channel",
		enabled: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Paste a Slack channel URL/iu)).toBeVisible();
		await expect(canvas.getByRole("button", { name: /^save$/iu })).toBeDisabled();
	},
};

/** Invalid time — the time field surfaces its HH:mm error and Save is disabled. */
export const InvalidTime: Story = {
	args: {
		hasSlackConnection: true,
		channelId: "C0974LJBPBK",
		enabled: true,
		scheduleTime: "9am",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Time must be in HH:mm format\./iu)).toBeVisible();
		await expect(canvas.getByRole("button", { name: /^save$/iu })).toBeDisabled();
	},
};

/**
 * Disconnect affordance — present only when the server exposes the active connection id.
 */
export const ConnectedWithDisconnect: Story = {
	args: {
		hasSlackConnection: true,
		slackConnectionId: 42,
		channelId: "C0974LJBPBK",
		teamLabel: "engineering",
		enabled: true,
	},
	play: async ({ canvas }) => {
		const trigger = canvas.getByRole("button", { name: /disconnect slack/iu });
		await expect(trigger).toBeVisible();
		await userEvent.click(trigger);
		// AlertDialog renders in a portal — query the whole document, not just the canvas.
		const dialog = await screen.findByRole("alertdialog", { name: /disconnect slack\?/iu });
		await expectSettledVisible(dialog);
		within(dialog).getByText(/the bot is uninstalled/iu);
		within(dialog).getByRole("button", { name: /^disconnect$/iu });
	},
};
