import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { ReasonCount } from "@/api/types.gen";
import {
	FEEDBACK_LOCATION_DEFS,
	type FeedbackLocation,
} from "@/components/practice-vocabulary/feedback-location-defs";
import { expectNoPageOverflow } from "@/stories/reflow";

import { parseSearch } from "@/lib/router-search";

import { FeedbackDeveloperSummary } from "./FeedbackDeveloperSummary";
import { reviewDevelopers } from "./fixtures";

const LOCATION_COUNTS: [FeedbackLocation, number][] = [
	["GITHUB", 4],
	["GITLAB", 2],
	["OTHER_WORK", 1],
	["HEPH", 2],
	["SLACK", 1],
	["NEXT_CONVERSATION", 2],
	["PRACTICE_PAGES", 3],
];
const EVERY_LOCATION: ReasonCount[] = LOCATION_COUNTS.map(([reason, count]) => ({
	reason,
	description: FEEDBACK_LOCATION_DEFS[reason].label,
	count,
}));

const meta = {
	component: FeedbackDeveloperSummary,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 1440] },
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		developerId: reviewDevelopers.grace.accountId,
		developerName: "Grace Hopper",
		state: { status: "ready", counts: EVERY_LOCATION },
	},
} satisfies Meta<typeof FeedbackDeveloperSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", { name: "Feedback for Grace Hopper" });
		const table = canvas.getByRole("table");
		for (const name of [
			"On GitHub",
			"On GitLab",
			"On the work",
			"In conversation in the app",
			"In conversation in Slack",
			"In conversation",
			"On the practice page",
		]) {
			within(table).getByRole("cell", { name });
		}
		const github = within(table).getByRole("row", { name: /On GitHub/u });
		const githubLink = within(github).getByRole("link", { name: "4" });
		await expect(
			parseSearch(new URL(String(githubLink.getAttribute("href")), window.location.origin).search),
		).toMatchObject({ developerId: "9", location: "GITHUB" });
		const all = within(table).getByRole("row", { name: /Everywhere/u });
		await expect(within(all).getByRole("link", { name: "15" })).toBeVisible();
	},
};

export const OneLocation: Story = {
	args: {
		state: {
			status: "ready",
			counts: [{ reason: "SLACK", description: FEEDBACK_LOCATION_DEFS.SLACK.label, count: 2 }],
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		within(table).getByRole("cell", { name: "In conversation in Slack" });
		await expect(within(table).queryByRole("cell", { name: "On GitHub" })).toBeNull();
	},
};

export const WithoutAName: Story = {
	args: { developerName: undefined },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", { name: "Feedback for this developer" });
	},
};

export const NoFeedbackYet: Story = {
	args: { state: { status: "ready", counts: [] } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("No feedback for Grace Hopper yet");
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
	},
};

export const LoadFailed: Story = {
	args: { state: { status: "error", error: { status: 500 }, onRetry: fn() } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("We could not count the feedback for Grace Hopper");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

export const Reflow: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", { name: "Feedback for Grace Hopper" });
		await expectNoPageOverflow();
	},
};
