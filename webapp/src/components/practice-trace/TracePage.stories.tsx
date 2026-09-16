import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { artifactTrace, untouchedArtifactTrace } from "./fixtures";
import { TracePage } from "./TracePage";

/**
 * One piece of work, everything recorded about it, and every practice's answer — the quiet ones
 * included.
 *
 * The route fetches the trace and owns the "Review this now" request; this screen composes the four
 * pieces below it, each of which has its own stories:
 * [Header](?path=/docs/practice-trace-header--docs),
 * [Refusal alert](?path=/docs/practice-trace-refusal-alert--docs),
 * [Signal timeline](?path=/docs/practice-trace-signal-timeline--docs) and
 * [Practice outcomes](?path=/docs/practice-trace-practice-outcomes--docs).
 */
const meta = {
	component: TracePage,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		canAdminister: true,
		trace: artifactTrace,
		isLoading: false,
		error: undefined,
		onRetry: fn(),
		onRequestReview: fn(),
		requestPending: false,
		refusal: undefined,
	},
} satisfies Meta<typeof TracePage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The whole page: what it is, what happened, and what each practice made of it. */
export const EveryOutcome: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: /Member-facing review activity/u }),
		).toBeVisible();
		await expect(canvas.getByRole("region", { name: "What we noticed" })).toBeVisible();
		await expect(
			canvas.getByRole("region", { name: "What each practice made of it" }),
		).toBeVisible();
		// Measured and delivered are two axes: this practice was reviewed and still said nothing.
		await expect(canvas.getByText("2 measurements, none sent")).toBeVisible();
		await expect(
			canvas.getByText(/Silence here is always a decision with a reason/u),
		).toBeVisible();
	},
};

export const Loading: Story = {
	args: { trace: undefined, isLoading: true },
	play: async ({ canvas }) => {
		// The label is `sr-only`, so it is present rather than visible — the query is the assertion.
		canvas.getByText("Loading review activity");
		// The way back out is on the page from the first frame, not only once the trace arrives.
		await expect(canvas.getByRole("link", { name: "Review activity" })).toHaveAttribute(
			"href",
			"/w/demo/reviews",
		);
	},
};

/** Nothing recorded about this artifact. A 404 is not retryable, so no button is offered. */
export const NotFound: Story = {
	args: {
		trace: undefined,
		error: { status: 404, title: "Not Found", detail: "Nothing recorded about this artifact." },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Couldn't load this work's review activity")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};

/** No answer at all — offline, or a request that never landed. Retrying is exactly right. */
export const LoadFailedWithoutAnAnswer: Story = {
	args: { trace: undefined, error: new TypeError("Failed to fetch") },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
	},
};

/** A refused ask is the workspace's own answer, so it is shown here rather than as a toast. */
export const RefusedTheAsk: Story = {
	args: {
		refusal: {
			status: "REFUSED",
			reason: "REVIEW_MODEL_UNBOUND",
			reasonDescription: "No AI model is set up to run reviews in this workspace.",
		},
	},
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(
			alert.getByText("No AI model is set up to run reviews in this workspace."),
		).toBeVisible();
		await expect(alert.getByRole("link", { name: "Set up a review model" })).toBeVisible();
	},
};

/** The same refusal for a member: `canAdminister` reaches the alert from the page's own prop. */
export const RefusedTheAskAsAMember: Story = {
	args: {
		canAdminister: false,
		refusal: {
			status: "REFUSED",
			reason: "REVIEW_MODEL_UNBOUND",
			reasonDescription: "No AI model is set up to run reviews in this workspace.",
		},
	},
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(alert.queryByRole("link")).not.toBeInTheDocument();
		// The timeline's own fix links go with it, so the page offers a member nothing they cannot
		// open. Scoped to the timeline: the header's "Open the original" is nobody's admin screen.
		const timeline = within(canvas.getByRole("region", { name: "What we noticed" }));
		await expect(
			timeline.queryByRole("link", { name: /^Open |^Set up /u }),
		).not.toBeInTheDocument();
	},
};

/** Recorded, and nothing followed. Every "why not" is on the page, none of it as an error. */
export const NothingWasReviewed: Story = {
	args: { trace: untouchedArtifactTrace },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Opened")).toBeVisible();
		await expect(
			canvas.getByText("No practice was watching for this when it happened."),
		).toBeVisible();
		await expect(canvas.getByText("Turned off")).toBeVisible();
	},
};

/** Both sections empty at once: nothing happened, and nothing covers this kind of work anyway. */
export const NothingReachedIt: Story = {
	args: { trace: { ...untouchedArtifactTrace, signals: [], practices: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing was recorded about this work")).toBeVisible();
		await expect(canvas.getByText("No practice covers this kind of work")).toBeVisible();
		// Named in the reader's words, not as `scm.issue`.
		await expect(canvas.getByText(/runs no practice against issue/u)).toBeVisible();
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Thin controllers")).toBeVisible();
		await expect(canvas.getByText("Waiting on a connection")).toBeVisible();
		await expectNoPageOverflow();
	},
};

/**
 * No trace and nothing that failed. `NotFound` above is the other shape — a 404 is an answer, and it
 * reads as one; this is a request that never got an answer at all.
 */
export const NeverArrived: Story = {
	args: { trace: undefined, error: undefined },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("This work's review activity hasn't loaded")).toBeVisible();
		await expect(canvas.queryByText("Couldn't load this work's review activity")).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
	},
};
