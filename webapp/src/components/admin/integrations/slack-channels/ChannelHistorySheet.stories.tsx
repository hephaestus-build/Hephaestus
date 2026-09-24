import type { Meta, StoryObj } from "@storybook/react";
import { delay, HttpResponse, http } from "msw";
import { expect, fn, screen, within } from "storybook/test";

import type { SlackChannelConsentEvent, SlackMonitoredChannel } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { daysBefore } from "@/stories/story-clock";

import { ChannelHistorySheet } from "./ChannelHistorySheet";

const CONSENT_EVENTS_URL = "*/slack/channels/:slackChannelId/consent-events";

const channel: SlackMonitoredChannel = {
	id: 1,
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
		id: 4,
		slackChannelId: channel.slackChannelId,
		toState: "ACTIVE",
		fromState: "PAUSED",
		createdAt: daysBefore(1),
		reason: "Course resumed",
	},
	{
		id: 3,
		slackChannelId: channel.slackChannelId,
		toState: "PAUSED",
		fromState: "ACTIVE",
		createdAt: daysBefore(4),
		reason: "Exam week",
	},
	{
		id: 2,
		slackChannelId: channel.slackChannelId,
		toState: "ACTIVE",
		fromState: "PENDING",
		createdAt: daysBefore(7),
	},
	{ id: 1, slackChannelId: channel.slackChannelId, toState: "PENDING", createdAt: daysBefore(9) },
];

const meta = {
	component: ChannelHistorySheet,
	parameters: {
		layout: "centered",
		// One MSW worker answers a whole Docs page, so each story gets its own frame until MSW goes.
		docs: { story: { inline: false, height: "600px" } },
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo-workspace",
		channel,
		onOpenChange: fn(),
	},
} satisfies Meta<typeof ChannelHistorySheet>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Populated: Story = {
	parameters: {
		msw: { handlers: [http.get(CONSENT_EVENTS_URL, () => HttpResponse.json(events))] },
	},
	play: async () => {
		const sheet = within(await screen.findByRole("dialog"));
		await sheet.findAllByText("Monitoring");
		sheet.getAllByText("Not started");
		sheet.getByText("Exam week");
		await expect(sheet.queryByText("ACTIVE")).not.toBeInTheDocument();
		await expect(sheet.queryByText("PENDING")).not.toBeInTheDocument();
	},
};

export const EmptyHistory: Story = {
	parameters: {
		msw: { handlers: [http.get(CONSENT_EVENTS_URL, () => HttpResponse.json([]))] },
	},
	play: async () => {
		const sheet = within(await screen.findByRole("dialog"));
		await expectSettledVisible(await sheet.findByText(/no consent changes recorded yet/iu));
	},
};

export const Loading: Story = {
	parameters: {
		msw: {
			handlers: [
				http.get(CONSENT_EVENTS_URL, async () => {
					await delay("infinite");
					return HttpResponse.json([]);
				}),
			],
		},
	},
};

export const LoadError: Story = {
	parameters: {
		msw: {
			handlers: [http.get(CONSENT_EVENTS_URL, () => new HttpResponse(null, { status: 500 }))],
		},
	},
	play: async () => {
		const sheet = within(await screen.findByRole("dialog"));
		await expectSettledVisible(await sheet.findByText(/could not load the consent history/iu));
		sheet.getByRole("button", { name: /^retry$/iu });
	},
};

export const Closed: Story = {
	args: { channel: null },
	play: async () => {
		await expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
	},
};
