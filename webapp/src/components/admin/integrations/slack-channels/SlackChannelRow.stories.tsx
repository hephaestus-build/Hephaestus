import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { SlackMonitoredChannel } from "@/api/types.gen";
import { Table, TableBody, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { expectSettledVisible } from "@/stories/overlay";
import { daysBefore } from "@/stories/story-clock";

import { SlackChannelRow } from "./SlackChannelRow";

const channel: SlackMonitoredChannel = {
	id: 1,
	slackTeamId: "T0000000000",
	slackChannelId: "C01ACTIVE001",
	channelName: "team-standup",
	consentState: "ACTIVE",
	optedOutMemberCount: 0,
	consentAnnouncedAt: daysBefore(2),
	createdAt: daysBefore(5),
};

/**
 * One allow-listed Slack channel. The consent state is rendered through the shared
 * `ConsentStateBadge`, so the row and the audit trail in the history sheet can never disagree
 * about what a state is called. Each state exposes only the transitions that are legal from it.
 */
const meta = {
	component: SlackChannelRow,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<Table>
				<TableHeader>
					<TableRow>
						<TableHead>Channel</TableHead>
						<TableHead>Status</TableHead>
						<TableHead className="text-right">Opted out</TableHead>
						<TableHead>Announced</TableHead>
						<TableHead className="w-0 text-right">
							<span className="sr-only">Actions</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					<Story />
				</TableBody>
			</Table>
		),
	],
	args: {
		channel,
		onActivate: fn(),
		onPause: fn(),
		onResume: fn(),
		onRemove: fn(),
		onSetUpAgain: fn(),
		onViewHistory: fn(),
	},
} satisfies Meta<typeof SlackChannelRow>;

export default meta;
type Story = StoryObj<typeof meta>;

/** PENDING — allow-listed but nothing is read yet; the only lifecycle action is Activate. */
export const NotStarted: Story = {
	args: {
		channel: {
			...channel,
			slackChannelId: "C02PENDING02",
			channelName: "team-intro",
			consentState: "PENDING",
			consentAnnouncedAt: undefined,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Not started");
		// No announcement yet — the empty timestamp is the screen-reader-visible "Never", not an
		// aria-hidden em-dash that vanishes for assistive tech.
		canvas.getByText("Never");

		await userEvent.click(canvas.getByRole("button", { name: /actions for team-intro/iu }));
		await expectSettledVisible(
			await screen.findByRole("menuitem", { name: /activate monitoring/iu }),
		);
		await expect(screen.queryByRole("menuitem", { name: /^pause$/iu })).not.toBeInTheDocument();
	},
};

/** ACTIVE — messages are being read; the row offers Pause, history and the destructive erase. */
export const Monitoring: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("Monitoring");
		// The announced time is the shared ticking RelativeTime, exposed as a real tooltip button so
		// its absolute time is one hover away and reachable by keyboard.
		const announced = canvas.getByRole("button", { name: /ago$/iu });
		await expect(announced.tagName).toBe("BUTTON");

		await userEvent.click(canvas.getByRole("button", { name: /actions for team-standup/iu }));
		await expectSettledVisible(await screen.findByRole("menuitem", { name: /^pause$/iu }));
		await expect(
			screen.queryByRole("menuitem", { name: /activate monitoring/iu }),
		).not.toBeInTheDocument();
	},
};

/** PAUSED — reading stopped, collected data kept; Resume is the way back. */
export const Paused: Story = {
	args: { channel: { ...channel, consentState: "PAUSED" } },
	play: async ({ canvas }) => {
		canvas.getByText("Paused");

		await userEvent.click(canvas.getByRole("button", { name: /actions for team-standup/iu }));
		await expectSettledVisible(await screen.findByRole("menuitem", { name: /^resume$/iu }));
	},
};

/** REVOKED — terminal. Nothing is left to erase, so only history and a fresh setup remain. */
export const Revoked: Story = {
	args: { channel: { ...channel, consentState: "REVOKED" } },
	play: async ({ canvas }) => {
		canvas.getByText("Revoked");

		await userEvent.click(canvas.getByRole("button", { name: /actions for team-standup/iu }));
		await expectSettledVisible(await screen.findByRole("menuitem", { name: /set up again/iu }));
		await expectSettledVisible(await screen.findByRole("menuitem", { name: /view history/iu }));

		await expect(
			screen.queryByRole("menuitem", { name: /activate monitoring/iu }),
		).not.toBeInTheDocument();
		await expect(screen.queryByRole("menuitem", { name: /^pause$/iu })).not.toBeInTheDocument();
		await expect(screen.queryByRole("menuitem", { name: /^resume$/iu })).not.toBeInTheDocument();
		await expect(
			screen.queryByRole("menuitem", { name: /remove & erase/iu }),
		).not.toBeInTheDocument();
	},
};

/** Members who opted out are a first-class trust signal, with a keyboard-reachable tooltip. */
export const WithOptOuts: Story = {
	args: { channel: { ...channel, optedOutMemberCount: 3 } },
	play: async ({ canvas }) => {
		// A bare non-interactive <span> trigger has no accessible role for keyboard/SR users; the
		// tooltip trigger must be a real, enabled <button> so Tab can reach it.
		const trigger = canvas.getByRole("button", { name: "3" });
		await expect(trigger.tagName).toBe("BUTTON");
		await expect(trigger).toBeEnabled();
	},
};

/** No name from Slack — the row falls back to the stable id rather than rendering "#undefined". */
export const NoChannelName: Story = {
	args: { channel: { ...channel, channelName: undefined } },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText(/C01ACTIVE001/u).length).toBeGreaterThan(0);
	},
};
