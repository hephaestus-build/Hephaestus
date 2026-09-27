import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	ada,
	assignedIssue,
	bob,
	draftPullRequest,
	gitLabMergeRequest,
	reviewRequest,
} from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";

import { WorkItemRow } from "./WorkItemRow";

const meta = {
	component: WorkItemRow,
	decorators: [
		// A row is a list item, so it renders inside the list it belongs to.
		(Story) => (
			<ul className="rounded-xl border bg-card">
				<Story />
			</ul>
		),
		withStandardPage,
	],
	tags: ["autodocs"],
	args: { work: reviewRequest, providerType: "GITHUB", login: ada.login },
} satisfies Meta<typeof WorkItemRow>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// Someone else's work names its author, and what it waits on is named, never only coloured.
		await expect(canvas.getByText("by Bob Brenner")).toBeVisible();
		await expect(canvas.getByText("Checks failing")).toBeVisible();
	},
};

/** The row is in the author's own list, so it names nobody. */
export const OwnWork: Story = {
	args: { work: gitLabMergeRequest, providerType: "GITLAB", login: bob.login },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/^by /u)).not.toBeInTheDocument();
		await expect(canvas.getByText("Approved")).toBeVisible();
	},
};

export const Draft: Story = {
	args: { work: draftPullRequest },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Draft")).toBeVisible();
	},
};

export const Issue: Story = {
	args: { work: assignedIssue },
};
