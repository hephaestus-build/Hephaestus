import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ReviewRunNotices } from "./ReviewRunNotices";
import { reviewJob } from "./story-mock-data";

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
	args: { job: completed, outputMayBeIncomplete: false },
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
		canvas.getByText("The review ended before it completed.");
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
		canvas.getByText(/parked rather than failed/u);
	},
};

/** A reason from a newer server. The label is humanised and the detail stays true of any hold. */
export const HeldForAnUnknownReason: Story = {
	args: { job: { ...completed, status: "QUEUED", holdReason: "PROVIDER_OUTAGE" } },
	play: async ({ canvas }) => {
		canvas.getByText("Provider outage");
		canvas.getByText(
			"This run is parked rather than failed. It resumes on its own once the hold lifts.",
		);
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
