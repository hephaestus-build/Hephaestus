import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, within } from "storybook/test";

import type { SlackChannelConsentEvent, SlackMonitoredChannel } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { daysBefore } from "@/stories/story-clock";

import { ChannelHistorySheet } from "./ChannelHistorySheet";

const channel: SlackMonitoredChannel = {
	id: "1",
	slackTeamId: "T0000000000",
	slackChannelId: "C02ACTIVE002",
	channelName: "team-standup",
	consentState: "ACTIVE",
	optedOutMemberCount: 0,
	consentAnnouncedAt: daysBefore(3),
	createdAt: daysBefore(9),
};

const events: SlackChannelConsentEvent[] = [
	{
		id: "4",
		slackChannelId: channel.slackChannelId,
		toState: "ACTIVE",
		fromState: "PAUSED",
		createdAt: daysBefore(1),
		reason: "Course resumed",
		actorAccountId: "7",
	},
	{
		id: "3",
		slackChannelId: channel.slackChannelId,
		toState: "PAUSED",
		fromState: "ACTIVE",
		createdAt: daysBefore(4),
		reason: "Exam week",
		actorAccountId: "7",
	},
	{
		id: "2",
		slackChannelId: channel.slackChannelId,
		toState: "ACTIVE",
		fromState: "PENDING",
		createdAt: daysBefore(7),
		reason: null,
		actorAccountId: "7",
	},
	{
		id: "1",
		slackChannelId: channel.slackChannelId,
		toState: "PENDING",
		fromState: null,
		createdAt: daysBefore(9),
		reason: null,
		actorAccountId: "7",
	},
];

const meta = {
	component: ChannelHistorySheet,
	parameters: {
		layout: "centered",
		// The sheet is a modal overlay: each story gets its own frame on the Docs page.
		docs: { story: { inline: false, height: "600px" } },
	},
	tags: ["autodocs"],
	args: {
		channel,
		history: { events, isLoading: false, onRetry: fn() },
		onOpenChange: fn(),
	},
} satisfies Meta<typeof ChannelHistorySheet>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Populated: Story = {
	play: async () => {
		const sheet = within(await screen.findByRole("dialog"));
		await sheet.findAllByText("Monitoring");
		sheet.getAllByText("Not started");
		sheet.getByText("Exam week");
		await expect(sheet.queryByText("ACTIVE")).not.toBeInTheDocument();
		await expect(sheet.queryByText("PENDING")).not.toBeInTheDocument();
		// The page behind is dimmed like behind a detail drawer, never blurred.
		const overlay = document.querySelector('[data-slot="sheet-overlay"]');
		await expect(overlay && getComputedStyle(overlay).backdropFilter).toBe("none");
	},
};

export const EmptyHistory: Story = {
	args: { history: { events: [], isLoading: false, onRetry: fn() } },
	play: async () => {
		const sheet = within(await screen.findByRole("dialog"));
		await expectSettledVisible(await sheet.findByText(/no consent changes recorded yet/iu));
	},
};

export const Loading: Story = {
	args: { history: { isLoading: true, onRetry: fn() } },
};

/** The read failed: Retry asks the route to read again. */
export const LoadError: Story = {
	args: {
		history: { isLoading: false, error: { status: 500 }, onRetry: fn() },
	},
	play: async ({ args, userEvent }) => {
		const sheet = within(await screen.findByRole("dialog"));
		await expectSettledVisible(await sheet.findByText(/could not load the consent history/iu));
		await userEvent.click(sheet.getByRole("button", { name: /^retry$/iu }));
		await expect(args.history.onRetry).toHaveBeenCalledOnce();
	},
};

export const Closed: Story = {
	args: { channel: null },
	play: async () => {
		await expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
	},
};
