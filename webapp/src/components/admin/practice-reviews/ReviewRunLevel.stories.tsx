import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { expectGenuinelyDisabled } from "@/test/controls";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	manyObservations,
	reviewFeedback,
	reviewJob,
	reviewObservations,
	workspacePractices,
} from "./fixtures";
import { reviewLevel } from "./review-levels";
import { REVIEW_PREVIEW_SIZE } from "./review-search";
import type { ReviewSectionState } from "./review-states";
import { ReviewRunLevel } from "./ReviewRunLevel";

const COMPLETED_RUN = "11111111-1111-1111-1111-111111111111";
const RUNNING_RUN = "aaaaaaaa-8888-8888-8888-888888888888";
const FAILED_RUN = "bbbbbbbb-8888-8888-8888-888888888888";

/**
 * A finished section: the first page of what a run produced, plus how many there are in all. The
 * total is not the length of the preview — the section links out to the full list precisely when the
 * two differ.
 */
function ready<T>(items: T[], total = items.length): ReviewSectionState<T> {
	return { status: "ready", items: items.slice(0, REVIEW_PREVIEW_SIZE), total };
}

function observationsOf(jobId: string): ReviewObservation[] {
	return reviewObservations.filter((observation) => observation.agentJobId === jobId);
}

function feedbackOf(jobId: string): ReviewFeedback[] {
	return reviewFeedback.filter((item) => item.agentJobId === jobId);
}

const NOTHING: ReviewSectionState<never> = { status: "ready", items: [], total: 0 };
const NOT_YET: ReviewSectionState<never> = { status: "pending" };

/**
 * One review, end to end: what it looked at, what it concluded, what it said, and how it ran — over
 * whichever list it was opened from.
 *
 * The level is handed its data. It is the route that keeps asking while a run is in flight, so every
 * state below — mid-flight, finished, stopped early, refused for want of evidence — is a prop here
 * rather than a moment you have to catch. The level has no page of its own, so every story mounts a
 * real drawer over a page.
 */
const meta = {
	component: ReviewRunLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		job: { status: "ready", job: reviewJob(COMPLETED_RUN) },
		observations: ready(observationsOf(COMPLETED_RUN)),
		feedback: ready(feedbackOf(COMPLETED_RUN)),
		practices: workspacePractices,
		onCancel: fn(),
		cancelPending: false,
		onRetryResultProcessing: fn(),
		retryResultProcessingPending: false,
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={reviewLevel(COMPLETED_RUN)} path={args.path} size="detailWide">
			{(level) => <ReviewRunLevel {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof ReviewRunLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const CompletedWithMixedOutput: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.getByRole("heading", {
				name: "Cache the workspace member lookup on the review path",
				level: 2,
			}),
		).toBeVisible();
		// The path starts at the page it was opened over, then names what this level is.
		await expect(
			within(panel.getByRole("list", { name: "Path" }))
				.getAllByRole("listitem")
				.map((crumb) => crumb.textContent),
		).toEqual(["Practice reviews", "Review"]);
		await expect(panel.getByText("Results processed")).toBeVisible();
		await panel.findByText("A cache miss and a permission failure come back as the same 404");
		await panel.findByText(/2 issues to tighten in this change/u);
		// A row opens its record as the next level rather than leaving the review.
		await expect(
			levelsOpenedBy(
				panel.getByRole("link", {
					name: "A cache miss and a permission failure come back as the same 404",
				}),
			),
		).toEqual(["observation:66666666-6666-6666-6666-666666666666"]);
		panel.getByRole("heading", { name: "How this review ran", level: 3 });
		panel.getByRole("button", { name: "Copy configuration" });
		// A finished, processed review owes nothing, so the level has no footer to offer.
		await expect(panel.queryByRole("button", { name: "Cancel review" })).not.toBeInTheDocument();
		await expect(
			panel.queryByRole("button", { name: "Retry result processing" }),
		).not.toBeInTheDocument();
	},
};

/**
 * At 320px the panel is the viewport. A row's status icon stays on its title's line: a row that
 * wrapped before its title would leave the icon alone above what it describes.
 */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await within(panel).findByText(/2 issues to tighten in this change/u);
		await expectNoPanelOverflow(panel);
		const rows = within(within(panel).getByRole("list", { name: "Observations" })).getAllByRole(
			"listitem",
		);
		for (const row of rows) {
			const icon = within(row).getAllByRole("button")[0];
			const title = within(row).getAllByRole("link")[0];
			if (icon === undefined || title === undefined) {
				throw new Error("A row with no status icon or no title has no line to share.");
			}
			await expect(icon.getBoundingClientRect().top).toBeGreaterThanOrEqual(
				title.getBoundingClientRect().top - 4,
			);
		}
	},
};

/** Processing can finish while approval still prevents publication. */
export const ProcessedWithFeedbackAwaitingApproval: Story = {
	args: {
		feedback: ready(
			feedbackOf(COMPLETED_RUN)
				.slice(0, 1)
				.map((item) => ({ ...item, deliveryState: "AWAITING_APPROVAL" as const })),
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await panel.findByText("Results processed");
		await panel.findByRole("button", { name: "Awaiting approval" });
		await expect(panel.queryByText("Summary posted")).not.toBeInTheDocument();
		await expect(panel.queryByText("Delivered")).not.toBeInTheDocument();
	},
};

/** The preview shows five; the section says how many there are and links to the rest. */
export const MoreObservationsThanItShows: Story = {
	args: {
		observations: ready(
			manyObservations(64).map((observation) => ({ ...observation, agentJobId: COMPLETED_RUN })),
			64,
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("link", { name: "See all 64 observations" })).toHaveAttribute(
			"href",
			`/w/demo/admin/practices/reviews/observations?agentJobId=${COMPLETED_RUN}`,
		);
	},
};

/**
 * A run that skipped automated review for insufficient evidence completes successfully with no
 * observations, exactly like a review that assessed the work and recorded none: the empty state
 * must distinguish the two.
 */
export const DeclinedForInsufficientEvidence: Story = {
	args: {
		job: {
			status: "ready",
			job: { ...reviewJob(COMPLETED_RUN), reviewOutcome: "INSUFFICIENT_EVIDENCE" },
		},
		observations: NOTHING,
		feedback: NOTHING,
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		// `findAllByText` resolves on the first match, so asserting a count on it races the second
		// section's render.
		await waitFor(async () =>
			expect(await panel.findAllByText("Nothing was assessed")).toHaveLength(2),
		);
		await expect(panel.queryByText("No observations were recorded")).toBeNull();
		await expect(panel.queryByText("No feedback")).toBeNull();
	},
};

/**
 * Nothing yet, and the reason is that the review is still going — not that it found nothing. A run in
 * flight is the one that can be stopped, so the footer offers exactly that, behind a confirmation.
 */
export const InProgressCanBeCancelled: Story = {
	args: {
		job: { status: "ready", job: reviewJob(RUNNING_RUN) },
		observations: NOT_YET,
		feedback: NOT_YET,
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Running")).toBeVisible();
		await expect(
			panel.getByText("Observations will appear when the review finishes."),
		).toBeVisible();
		await expect(panel.getByText("Feedback will appear when the review finishes.")).toBeVisible();
		await expect(panel.queryByText("No observations were recorded")).not.toBeInTheDocument();
		await expect(
			panel.queryByRole("button", { name: "Retry result processing" }),
		).not.toBeInTheDocument();

		await userEvent.click(panel.getByRole("button", { name: "Cancel review" }));
		const confirm = await screen.findByRole("alertdialog", { name: "Cancel this review?" });
		await userEvent.click(within(confirm).getByRole("button", { name: "Cancel review" }));
		await expect(args.onCancel).toHaveBeenCalledOnce();
	},
};

/** Cancelling is under way: the footer says so rather than offering the same press again. */
export const Cancelling: Story = {
	args: {
		job: { status: "ready", job: reviewJob(RUNNING_RUN) },
		observations: NOT_YET,
		feedback: NOT_YET,
		cancelPending: true,
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Cancelling…" }));
	},
};

/**
 * The review finished and its results could not be processed: the one other thing the footer can
 * offer, and the only thing it offers here.
 */
export const ResultProcessingFailed: Story = {
	args: {
		job: { status: "ready", job: { ...reviewJob(COMPLETED_RUN), deliveryStatus: "FAILED" } },
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("button", { name: "Cancel review" })).not.toBeInTheDocument();
		await userEvent.click(panel.getByRole("button", { name: "Retry result processing" }));
		const confirm = await screen.findByRole("alertdialog", { name: "Retry result processing?" });
		await userEvent.click(within(confirm).getByRole("button", { name: /Retry/u }));
		await expect(args.onRetryResultProcessing).toHaveBeenCalledOnce();
	},
};

/** Nothing at all, and it never will: the empty state answers the question the sections would. */
export const FailedWithoutOutput: Story = {
	args: {
		job: { status: "ready", job: reviewJob(FAILED_RUN) },
		observations: NOTHING,
		feedback: NOTHING,
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Review couldn’t be completed")).toBeVisible();
		await expect(
			panel.getByText("This review ended before it produced observations or feedback."),
		).toBeVisible();
		// The failure text is on the level rather than behind a disclosure: this is the only surface
		// that can say why a review produced nothing.
		panel.getByText(/Cannot compute diff/u);
		await expect(panel.queryByRole("heading", { name: "Observations" })).not.toBeInTheDocument();
		// A run that ended has nothing left to cancel.
		await expect(panel.queryByRole("button", { name: "Cancel review" })).not.toBeInTheDocument();
	},
};

/** Something, but not everything — so the sections stay and a banner says what they are. */
export const FailedWithPartialOutput: Story = {
	args: {
		job: { status: "ready", job: reviewJob(FAILED_RUN) },
		observations: ready(
			manyObservations(1).map((observation) => ({ ...observation, agentJobId: FAILED_RUN })),
		),
		feedback: NOTHING,
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Review output may be incomplete")).toBeVisible();
		panel.getByText("A dropped delivery is logged at debug and never counted");
		await expect(panel.queryByText("Review couldn’t be completed")).not.toBeInTheDocument();
	},
};

/** The run is in, its output is not. The sections draw their own skeletons, not the level's. */
export const OutputStillLoading: Story = {
	args: { observations: { status: "loading" }, feedback: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("heading", {
			name: "Cache the workspace member lookup on the review path",
			level: 2,
		});
		panel.getByText("Loading observations");
		panel.getByText("Loading feedback");
	},
};

/**
 * The drawer is named by its title, so the heading stands while the run loads; only its words wait.
 * The path stays usable, so a reader who opened the wrong row can go back without waiting.
 */
export const Loading: Story = {
	args: { job: { status: "loading" } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveAccessibleName("Loading review");
		await expect(panel.queryByText("Couldn't load this review")).not.toBeInTheDocument();
		await userEvent.click(panel.getByRole("button", { name: "Practice reviews" }));
		await expect(args.path.onClose).toHaveBeenCalledWith(0);
	},
};

/** The path survives the failure, so a reader who cannot see this review can still leave. */
export const LoadFailed: Story = {
	args: {
		job: {
			status: "error",
			error: { status: 500, detail: "The review could not be read." },
			onRetry: fn(),
		},
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Couldn't load this review")).toBeVisible();
		// With no record to name it, the level is named for what it is.
		await expect(screen.getByRole("dialog")).toHaveAccessibleName("Review");
		panel.getByRole("button", { name: "Practice reviews" });
		await userEvent.click(panel.getByRole("button", { name: "Retry" }));
		if (args.job.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(args.job.onRetry).toHaveBeenCalledOnce();
	},
};

/** One section can fail while the other answers; each carries its own retry. */
export const OneSectionFailed: Story = {
	args: {
		observations: {
			status: "error",
			error: { status: 503, detail: "The observation index is unavailable." },
			onRetry: fn(),
		},
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Couldn't load observations")).toBeVisible();
		// The other section is unaffected, which is the whole point of two states rather than one.
		await panel.findByText(/2 issues to tighten in this change/u);
	},
};
