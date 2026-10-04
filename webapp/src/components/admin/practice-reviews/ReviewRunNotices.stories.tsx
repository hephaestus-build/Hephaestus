import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { levelsOpenedBy } from "@/test/detail-stack";

import { reviewJob } from "./fixtures";
import { ReviewRunNotices } from "./ReviewRunNotices";

const completed = reviewJob("11111111-1111-1111-1111-111111111111");
const failed = reviewJob("bbbbbbbb-8888-8888-8888-888888888888");

/**
 * The banners above a review's output: what to know before reading it.
 *
 * A held run is the one worth getting right. It is parked, not broken — it starts again on its own
 * — so nothing here says "failed", and the copy names what lifts the hold. An unknown hold reason
 * still has to read as English, because the server may add one this build has never heard of.
 */
const meta = {
	component: ReviewRunNotices,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: { job: completed, practices: undefined, outputMayBeIncomplete: false },
} satisfies Meta<typeof ReviewRunNotices>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A review that ran to the end and is not waiting for anything says nothing at all. */
export const NothingToSay: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
	},
};

/** A failed run that still produced something: destructive, because something did go wrong. */
export const OutputMayBeIncomplete: Story = {
	args: { job: failed, outputMayBeIncomplete: true },
	play: async ({ canvas }) => {
		canvas.getByText("Review output may be incomplete");
		canvas.getByText(
			"The review ended early. The observations and feedback below may be only part of what it would have found.",
		);
	},
};

/**
 * A cancelled run stopped on purpose, so the same banner drops to the neutral tone: the reader asked
 * for this, and colouring it as a failure would tell them their own action broke something.
 */
export const StoppedOnPurpose: Story = {
	args: { job: { ...failed, status: "CANCELLED" }, outputMayBeIncomplete: true },
	play: async ({ canvas }) => {
		canvas.getByText("Review output may be incomplete");
	},
};

/** The hold this build knows: it names the cap, who can lift it, and that it lifts by itself. */
export const HeldForBudget: Story = {
	args: { job: { ...completed, status: "QUEUED", holdReason: "BUDGET" } },
	play: async ({ canvas }) => {
		canvas.getByText("Over the AI budget");
		canvas.getByText(/this review is waiting, not failed/u);
	},
};

/** A reason from a newer server. The label is a plain hold and the detail stays true of any hold. */
export const HeldForAnUnknownReason: Story = {
	args: { job: { ...completed, status: "QUEUED", holdReason: "PROVIDER_OUTAGE" } },
	play: async ({ canvas }) => {
		canvas.getByText("On hold");
		await expect(canvas.queryByText(/PROVIDER_OUTAGE|Provider outage/u)).not.toBeInTheDocument();
		canvas.getByText(
			"This review is waiting, not failed. It continues on its own when the hold ends.",
		);
	},
};

/**
 * A push review that asked fewer practices than were ready, because earlier reviews of the same code
 * had already answered the rest. Each answered practice links to the review that answered it,
 * so the gap between ready and asked reads as reuse, not as practices the review skipped. A practice
 * whose name has not loaded is named by its slug, so two of them never read alike.
 */
export const AnsweredByAnEarlierReview: Story = {
	args: {
		practices: [
			{ slug: "removes-duplication-instead-of-copy-pasting", name: "Remove duplication" },
		],
		job: {
			...completed,
			answeredPractices: [
				{
					practiceSlug: "removes-duplication-instead-of-copy-pasting",
					revisionId: 1822,
					reviewId: "aaaaaaaa-1111-1111-1111-111111111111",
				},
				{
					practiceSlug: "handles-errors-instead-of-swallowing-them",
					revisionId: 1838,
					reviewId: "cccccccc-1111-1111-1111-111111111111",
				},
			],
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("2 practices were answered by an earlier review");
		await expect(canvas.getByRole("link", { name: "Remove duplication" })).toBeVisible();
		await expect(
			canvas.getByRole("link", { name: "handles-errors-instead-of-swallowing-them" }),
		).toBeVisible();
		const [first, second] = canvas.getAllByRole("link", { name: "an earlier review" });
		if (first === undefined || second === undefined) {
			throw new Error("Each answered practice links to the review that answered it.");
		}
		await expect(levelsOpenedBy(first)).toContain("review:aaaaaaaa-1111-1111-1111-111111111111");
		await expect(levelsOpenedBy(second)).toContain("review:cccccccc-1111-1111-1111-111111111111");
	},
};

/** Both at once, in the order a reader needs them: what the output is, then why it is waiting. */
export const IncompleteAndHeld: Story = {
	args: {
		job: { ...completed, status: "QUEUED", holdReason: "BUDGET" },
		outputMayBeIncomplete: true,
	},
	play: async ({ canvas }) => {
		canvas.getByText("Review output may be incomplete");
		canvas.getByText("Over the AI budget");
	},
};
