import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ItemGroup } from "@/components/ui/item";

import { tracedArtifact } from "./story-mock-data";
import { TracedArtifactRow } from "./TracedArtifactRow";

const pullRequestRow = tracedArtifact(1423);
const issueWithNoReviews = tracedArtifact(1430);
const unlinkableConversation = tracedArtifact(88);

/**
 * One row of the review-activity list. The whole row is the link into the trace, and the two counts
 * are kept apart on purpose: how much was recorded, and how much of it started a review.
 */
const meta = {
	component: TracedArtifactRow,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<ItemGroup>
				<Story />
			</ItemGroup>
		),
	],
	args: { workspaceSlug: "demo", artifact: pullRequestRow },
} satisfies Meta<typeof TracedArtifactRow>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PullRequest: Story = {
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link");
		await expect(link).toHaveAttribute("href", "/w/demo/reviews/scm.pull_request/1423");
		await expect(canvas.getByText("ls1intum/Hephaestus")).toBeVisible();
		await expect(canvas.getByText("#1423")).toBeVisible();
		await expect(canvas.getByText("6 moments recorded · 2 started a review")).toBeVisible();
	},
};

/** Nothing was reviewed yet, and the sentence says so rather than leaving the second count off. */
export const NothingReviewedYet: Story = {
	args: { artifact: issueWithNoReviews },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("2 moments recorded · 0 started a review")).toBeVisible();
	},
};

/**
 * No number, no container, no upstream link — a deleted or unlinkable artifact still lists, and the
 * kind is still named for a screen reader beside the icon.
 */
export const UnlinkableArtifact: Story = {
	args: { artifact: unlinkableConversation },
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link");
		await expect(link).toHaveAttribute("href", "/w/demo/reviews/chat.conversation_thread/88");
		// The kind rides in the accessible name, not only in the icon: this row's title happens to be
		// the word "Conversation" too, and only one of the two is readable without sight.
		await expect(link).toHaveAccessibleName(/Conversation/u);
	},
};

/** A long title wraps rather than truncating: the title is what a reader recognises the work by. */
export const LongTitle: Story = {
	args: {
		artifact: {
			...pullRequestRow,
			title:
				"Say why a practice stayed quiet on a pull request, and where a workspace admin goes to change the answer, in a title long enough to wrap on any viewport",
		},
	},
};
