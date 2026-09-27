import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { QUIET_SUMMARY, SUMMARY } from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";

import { ActivitySummaryList } from "./ActivitySummaryList";

const meta = {
	component: ActivitySummaryList,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: { status: "ready", summary: SUMMARY },
		providerType: "GITHUB",
		range: "7d",
	},
} satisfies Meta<typeof ActivitySummaryList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// The link is described by its row's figures, so what it opens is heard with what it counts.
		const reviews = canvas.getByRole("link", { name: "Reviews" });
		await expect(reviews).toHaveAccessibleDescription(
			"four approvals, one change request and two comment-only reviews",
		);
		await expect(reviews).toHaveAttribute("href", expect.stringContaining("activity%3Areviews"));
		// A clause with a count of ten or more writes all of its counts as digits.
		await expect(canvas.getByRole("link", { name: "Comments" })).toHaveAccessibleDescription(
			"9 in conversations and 12 on code",
		);
	},
};

/** Only a row with something behind it opens; the others say there is nothing in the range. */
export const SomeKindsQuiet: Story = {
	args: {
		state: {
			status: "ready",
			summary: { ...QUIET_SUMMARY, pullRequestsMerged: 1, codeComments: 3 },
		},
	},
	play: async ({ canvas }) => {
		const links = canvas.getAllByRole("link").map((link) => link.textContent);
		await expect(links).toStrictEqual(["Pull requests", "Comments"]);
		await expect(canvas.getAllByText("None in the last 7 days")).toHaveLength(2);
	},
};

export const Quiet: Story = {
	args: { state: { status: "ready", summary: QUIET_SUMMARY } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
		await expect(canvas.getAllByText("None in the last 7 days")).toHaveLength(4);
	},
};

export const GitLab: Story = {
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Merge requests" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
};
