import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor } from "storybook/test";

import type { ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";

import {
	manyObservations,
	reviewFeedback,
	reviewJob,
	reviewObservations,
	workspacePractices,
} from "./fixtures";
import { REVIEW_PREVIEW_SIZE, type ReviewSectionState } from "./ReviewOutputSections";
import { ReviewRunDetailPage } from "./ReviewRunDetailPage";

const COMPLETED_RUN = "11111111-1111-1111-1111-111111111111";
const CONVERSATION_RUN = "33333333-3333-3333-3333-333333333333";
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

/** The practice one of the completed run's observations names, and the one the card is read on. */
const THIN_CONTROLLERS = workspacePractices.find((p) => p.slug === "thin-controllers");
if (!THIN_CONTROLLERS) {
	throw new Error("The practice fixtures no longer cover thin-controllers");
}

/**
 * One review, end to end: what it looked at, what it concluded, what it said, and how it ran.
 *
 * The screen is handed its data. It is the route that keeps asking while a run is in flight, so
 * every state below — mid-flight, finished, stopped early, refused for want of evidence — is a
 * prop here rather than a moment you have to catch.
 */
const meta = {
	component: ReviewRunDetailPage,
	parameters: {
		layout: "padded",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		jobId: COMPLETED_RUN,
		search: {},
		job: reviewJob(COMPLETED_RUN),
		isLoading: false,
		error: null,
		onRetry: fn(),
		observations: ready(observationsOf(COMPLETED_RUN)),
		feedback: ready(feedbackOf(COMPLETED_RUN)),
		// An observation row names its practice but carries none of its prose, so the screen is handed
		// the list and each row reads its own record out of it. See `ReviewPracticeLink`.
		practices: workspacePractices,
		onCancel: fn(),
		cancelPending: false,
		onRetryResultProcessing: fn(),
		retryResultProcessingPending: false,
	},
} satisfies Meta<typeof ReviewRunDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const CompletedWithMixedOutput: Story = {
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", {
			name: "Cache the workspace member lookup on the review path",
		});
		canvas.getByText("Results processed");
		await canvas.findByText("A cache miss and a permission failure come back as the same 404");
		await canvas.findByText(/2 issues to tighten in this change/u);
		await canvas.findByRole("heading", { name: "How this review ran", level: 3 });
		canvas.getByRole("button", { name: "Copy configuration" });
		canvas.getByText("Tokens read");
		await expect(canvas.queryByText("Configuration snapshot")).not.toBeInTheDocument();
		await expectNoPageOverflow();
	},
};

/** Processing can finish while approval still prevents publication. */
export const ProcessedWithFeedbackAwaitingApproval: Story = {
	args: {
		feedback: ready(
			feedbackOf(COMPLETED_RUN)
				.slice(0, 1)
				.map((item) => ({
					...item,
					deliveryState: "AWAITING_APPROVAL" as const,
				})),
		),
	},
	play: async ({ canvas }) => {
		await canvas.findByText("Results processed");
		await canvas.findByText("Awaiting approval");
		await expect(canvas.queryByText("Summary posted")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Delivered")).not.toBeInTheDocument();
	},
};

/**
 * A practice named on an observation row does two things here as well: it opens the practice, and it
 * says what the practice is without leaving the review. The card is the half that goes quiet on its
 * own — a row that stops being handed its practice record still renders a perfectly good link.
 */
export const PracticeOpensItsDefinition: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		const link = await canvas.findByRole("link", { name: /Thin controllers/u });
		await expect(link).toHaveAttribute("href", "/w/demo/admin/practices/thin-controllers");
		// The card is a portal, so it is looked for on the whole screen rather than in the canvas.
		await userEvent.hover(link);
		await screen.findByText(THIN_CONTROLLERS.whyItMatters ?? "");
		await screen.findByText(THIN_CONTROLLERS.whatGoodLooksLike ?? "");
	},
};

/** The preview shows five; the section says how many there are and links to the rest. */
export const MoreObservationsThanItShows: Story = {
	args: {
		observations: ready(
			manyObservations(64).map((observation) => ({
				...observation,
				agentJobId: COMPLETED_RUN,
			})),
			64,
		),
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("link", { name: "See all 64 observations" });
	},
};

/**
 * A run that skipped automated review for insufficient evidence completes successfully with no
 * observations, exactly like a review that assessed the work and recorded none: the empty state
 * must distinguish the two.
 */
export const DeclinedForInsufficientEvidence: Story = {
	args: {
		job: { ...reviewJob(COMPLETED_RUN), reviewOutcome: "INSUFFICIENT_EVIDENCE" },
		observations: NOTHING,
		feedback: NOTHING,
	},
	play: async ({ canvas }) => {
		// `findAllByText` resolves on the first match, so asserting a count on it races the second
		// panel's render.
		await waitFor(async () =>
			expect(await canvas.findAllByText("Nothing was assessed")).toHaveLength(2),
		);
		await expect(canvas.queryByText("No observations were recorded")).toBeNull();
		await expect(canvas.queryByText("No feedback")).toBeNull();
		await expect(
			await canvas.findAllByText(/the material it needed was missing, unreadable, out of date/u),
		).not.toHaveLength(0);
	},
};

/** Nothing yet, and the reason is that the review is still going — not that it found nothing. */
export const InProgressWithoutOutput: Story = {
	args: {
		jobId: RUNNING_RUN,
		job: reviewJob(RUNNING_RUN),
		observations: NOT_YET,
		feedback: NOT_YET,
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Running")).toBeVisible();
		await expect(
			await canvas.findByText("Observations will appear when the review finishes."),
		).toBeVisible();
		await expect(
			await canvas.findByText("Feedback will appear when the review finishes."),
		).toBeVisible();
		await expect(canvas.queryByText("No observations were recorded")).not.toBeInTheDocument();
		await expect(canvas.queryByText("No feedback")).not.toBeInTheDocument();
		// A run in flight is the one that can be stopped.
		canvas.getByRole("button", { name: "Cancel review" });
	},
};

/** Nothing at all, and it never will: the empty state answers the question the sections would. */
export const FailedWithoutOutput: Story = {
	args: {
		jobId: FAILED_RUN,
		job: reviewJob(FAILED_RUN),
		observations: NOTHING,
		feedback: NOTHING,
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Review couldn't be completed")).toBeVisible();
		await expect(
			await canvas.findByText("This review ended before it produced observations or feedback."),
		).toBeVisible();
		// The failure text is on the page rather than behind a disclosure: this is the only screen that
		// can say why a review produced nothing.
		await canvas.findByText(/Cannot compute diff/u);
		await expect(canvas.queryByText("Technical details")).not.toBeInTheDocument();
	},
};

/** Something, but not everything — so the sections stay and a banner says what they are. */
export const FailedWithPartialOutput: Story = {
	args: {
		jobId: FAILED_RUN,
		job: reviewJob(FAILED_RUN),
		observations: ready(
			manyObservations(1).map((observation) => ({ ...observation, agentJobId: FAILED_RUN })),
		),
		feedback: NOTHING,
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Review output may be incomplete")).toBeVisible();
		await canvas.findByText("A dropped delivery is logged at debug and never counted");
	},
};

/** Not every review reads a pull request; the header names whatever was reviewed. */
export const ReviewOfAConversation: Story = {
	args: {
		jobId: CONVERSATION_RUN,
		job: reviewJob(CONVERSATION_RUN),
		observations: ready(observationsOf(CONVERSATION_RUN)),
		feedback: ready(feedbackOf(CONVERSATION_RUN)),
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", { name: "How should we roll back the pricing migration?" });
		await canvas.findByText("The thread ends without naming what was chosen");
	},
};

/** The run is in, its output is not. The sections draw their own skeletons, not the page's. */
export const OutputStillLoading: Story = {
	args: { observations: { status: "loading" }, feedback: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", {
			name: "Cache the workspace member lookup on the review path",
		});
		canvas.getByText("Loading observations");
		canvas.getByText("Loading feedback");
	},
};

/** Nothing to show above the breadcrumb until the run itself is in. */
export const Loading: Story = {
	args: { job: undefined, isLoading: true },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, canvasElement }) => {
		await expect(canvasElement.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(
			0,
		);
		canvas.getByRole("link", { name: "Reviews" });
		await expect(canvas.queryByRole("heading", { level: 2 })).not.toBeInTheDocument();
	},
};

/** The breadcrumb survives the failure, so a reader who cannot see this review can still leave. */
export const LoadFailed: Story = {
	args: {
		job: undefined,
		error: { status: 500, detail: "The review could not be read." },
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ args, canvas, userEvent }) => {
		await canvas.findByText("Couldn't load this review");
		canvas.getByRole("link", { name: "Reviews" });
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalled();
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
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("Couldn't load observations");
		// The other section is unaffected, which is the whole point of two states rather than one.
		await canvas.findByText(/2 issues to tighten in this change/u);
	},
};
