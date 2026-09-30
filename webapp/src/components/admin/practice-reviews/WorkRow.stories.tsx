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
		// The provider's noun and the number it gave the work, which the title alone does not carry.
		canvas.getByText("Pull request #1423");
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

/** GitLab calls the same kind of work a merge request, and numbers it with a `!`. */
export const MergeRequest: Story = {
	args: {
		work: {
			...tracedArtifact(1423),
			reviewedWork: {
				...tracedArtifact(1423).reviewedWork,
				provider: "GITLAB",
				label: "!1423",
				url: "https://gitlab.example.com/hephaestus/hephaestus/-/merge_requests/1423",
			},
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Merge request !1423");
		await expect(canvas.queryByText(/pull request/iu)).not.toBeInTheDocument();
	},
};

/**
 * A kind this build cannot address has no level to open, so its title is a word, not a link, and
 * the row still says what it is in words rather than by the server's name for the kind.
 */
export const UnknownKind: Story = {
	args: {
		work: {
			...tracedArtifact(88),
			artifactKind: "tracker.ticket",
			reviewedWork: { id: "88", kind: "tracker.ticket", label: "T-88", title: "Ticket 88" },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Ticket 88");
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
		canvas.getByText("Other work");
		await expect(canvas.queryByText(/tracker\./u)).not.toBeInTheDocument();
	},
};

/**
 * Work of a kind nobody declares any more, which the server can no longer name: it sends "Other work"
 * as the label, and the row reads that in both places rather than the kind's id or the work's number.
 */
export const UnknownKindTheServerCannotName: Story = {
	args: {
		work: {
			...tracedArtifact(88),
			artifactKind: "tracker.ticket",
			reviewedWork: { id: "88", kind: "tracker.ticket", label: "Other work" },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Other work")).toHaveLength(2);
		await expect(canvas.queryByText(/tracker|ticket|\b88\b/iu)).not.toBeInTheDocument();
	},
};

/** An Outline document is named by its title and sits in its collection, which the row names. */
export const OutlineDocument: Story = {
	args: { work: tracedArtifact(512) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Onboarding: your first week")).toBeVisible();
		await expect(canvas.getByText("Document")).toBeVisible();
		await expect(canvas.getByText("Engineering handbook")).toBeVisible();
	},
};
