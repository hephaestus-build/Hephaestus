import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { tracedArtifact } from "@/components/practice-trace/fixtures";
import { levelsOpenedBy } from "@/test/detail-stack";

import { ReviewRowList } from "./ReviewRow";
import { WorkRow } from "./WorkRow";

/**
 * One row of the Work list: whether anything recorded about the work started a review as the leading
 * icon, the work's title as the link to its level, and how many of the moments recorded on it went
 * on to a review.
 */
const meta = {
	component: WorkRow,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: { work: tracedArtifact(1423) },
	decorators: [
		(Story) => (
			<ReviewRowList label="Work, most recent first">
				<Story />
			</ReviewRowList>
		),
	],
} satisfies Meta<typeof WorkRow>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ReviewStarted: Story = {
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: "Review started" });
		// The title opens the work's level over the list, under the slug its level is addressed by.
		await expect(
			levelsOpenedBy(
				canvas.getByRole("link", {
					name: "Member-facing review activity: say why a practice stayed quiet",
				}),
			),
		).toEqual(["work:pull-request:1423"]);
		canvas.getByText("Pull or merge request");
		canvas.getByText("6 moments recorded · 2 started a review");
	},
};

/** Moments were recorded and none of them started a review: the row an admin opens to learn why. */
export const NoReviewStarted: Story = {
	args: { work: tracedArtifact(1430) },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: "No review started" });
		canvas.getByText("2 moments recorded · 0 started a review");
	},
};

/** A kind this build cannot address has no level to open, so its title is a word, not a link. */
export const UnknownKind: Story = {
	args: { work: { ...tracedArtifact(88), artifactKind: "tracker.ticket", title: "Ticket 88" } },
	play: async ({ canvas }) => {
		canvas.getByText("Ticket 88");
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
		// The raw kind rather than nothing, so a kind added on the server stays legible.
		canvas.getByText("tracker.ticket");
	},
};
