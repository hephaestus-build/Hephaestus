import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { SlackMonitoredChannel } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";

import { ActivateChannelDialog } from "./ActivateChannelDialog";

const channel: SlackMonitoredChannel = {
	id: 1,
	slackTeamId: "T0000000000",
	slackChannelId: "C02PENDING02",
	channelName: "team-standup",
	consentState: "PENDING",
	optedOutMemberCount: 0,
	createdAt: new Date("2026-07-01T00:00:00Z"),
};

/**
 * Affirmative-consent step before monitoring a Slack channel: a Dialog, not a Switch, because
 * activation posts a public announcement, begins reading messages forward-only, and lets members
 * opt out. The dialog is portalled, so the plays query the document rather than the story canvas.
 */
const meta = {
	component: ActivateChannelDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		channel,
		onOpenChange: fn(),
		onConfirm: fn(),
	},
} satisfies Meta<typeof ActivateChannelDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Open — the three consequences are spelled out and confirming reports the channel back. */
export const Open: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		dialog.getByText(/post a visible announcement/iu);
		dialog.getByText(/begin reading new messages/iu);
		dialog.getByText(/opt out/iu);

		await userEvent.click(dialog.getByRole("button", { name: /activate monitoring/iu }));
		await expect(args.onConfirm).toHaveBeenCalledWith(channel);
	},
};

/** Falls back to the stable id when Slack never gave the channel a name. */
export const NoChannelName: Story = {
	args: { channel: { ...channel, channelName: undefined } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getAllByText(/#C02PENDING02/u).length).toBeGreaterThan(0);
	},
};

/** A rejected activation keeps the dialog open so the admin can retry. */
export const Rejected: Story = {
	args: {
		onConfirm: fn(async () => {
			throw new Error("slack rejected the activation");
		}),
	},
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: /activate monitoring/iu }));
		await expectSettledVisible(await screen.findByRole("dialog"));
	},
};

/** Closed — nothing is rendered until a channel is chosen for activation. */
export const Closed: Story = {
	args: { channel: null },
	play: async () => {
		await expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
	},
};
