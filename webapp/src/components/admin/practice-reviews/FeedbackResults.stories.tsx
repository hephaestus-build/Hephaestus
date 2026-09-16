import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectNoPageOverflow } from "@/test/reflow";

import { FeedbackResults } from "./FeedbackResults";
import { reviewFeedback } from "./story-mock-data";

const [firstFeedback] = reviewFeedback;
if (!firstFeedback) {
	throw new Error("The review fixtures must contain a piece of feedback");
}

/** Storybook resets a spy that appears in `args` between runs, so one instance is enough. */
const clearFilters = fn();

const meta = {
	component: FeedbackResults,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: { workspaceSlug: "demo", state: { status: "ready", feedback: reviewFeedback } },
} satisfies Meta<typeof FeedbackResults>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Every outcome, on both places feedback can go, with a withheld row from each reason family. */
export const Default: Story = {
	play: async ({ canvas }) => {
		// A conversation row's outcome is refined by its place, and still begins with the stem of the
		// stored state so the Outcome facet remains findable from the row.
		canvas.getByText("Prepared for conversation");
		canvas.getByText("Delivered in conversation");
		canvas.getByText("Failed to deliver");
		canvas.getByText("Replaced by newer");
		// A withheld row carries its own precise reason; the badge only says something stopped it.
		canvas.getByText("The work was already merged, so a note on it would arrive too late.");
		canvas.getByText("The developer has opted out of AI feedback.");
		canvas.getByText("Found while reviewing past work, which is measured but never sent.");
		canvas.getByText("Nearly the same as other feedback from the same review.");
		await expect(canvas.queryAllByText(/Feedback for/u)).toHaveLength(0);
	},
};

/**
 * The wire preview of a real note is a lead line, a bold heading, a backticked file locator and the
 * opening of a fenced Java block. The row shows the sentence a person would read out and marks the
 * cut; the code and the markers do not appear.
 */
export const LongFeedback: Story = {
	args: {
		state: {
			status: "ready",
			feedback: reviewFeedback.filter((item) => item.bodyTruncated),
		},
	},
	parameters: { chromatic: { viewports: [320, 1440] } },
	play: async ({ canvas }) => {
		const title = await canvas.findByRole("link", {
			name: /2 issues to tighten in this change, plus one thing worth keeping/u,
		});
		await expect(title).toHaveAccessibleName(expect.stringContaining("…"));
		await expect(title).not.toHaveAccessibleName(expect.stringContaining("**"));
		await expect(canvas.queryByText(/```/u)).not.toBeInTheDocument();
		await expectNoPageOverflow();
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Withheld")).toHaveLength(4);
		await expectNoPageOverflow();
	},
};

export const WithoutAPreview: Story = {
	args: {
		state: {
			status: "ready",
			feedback: [{ ...firstFeedback, bodyPreview: undefined, bodyTruncated: false }],
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		canvas.getByRole("link", { name: "No feedback text was composed" });
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
};

export const Empty: Story = {
	args: { state: { status: "empty", filtered: false } },
	parameters: { chromatic: { viewports: [1440] } },
};

export const FilteredToNothing: Story = {
	args: { state: { status: "empty", filtered: true, onClearFilters: clearFilters } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await expect(clearFilters).toHaveBeenCalled();
	},
};
