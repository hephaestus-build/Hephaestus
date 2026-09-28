import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	awaitingApprovalFeedback,
	failedFeedback,
	failedReviewRuns,
	reviewOverviewScope,
} from "./fixtures";
import type { ReviewListTarget } from "./review-outcomes";
import type { ReviewSectionState } from "./review-states";
import { ReviewAttention } from "./ReviewAttention";

function ready<T>(items: T[], total = items.length): ReviewSectionState<T> {
	return { status: "ready", items, total };
}

const NOTHING: ReviewSectionState<never> = { status: "ready", items: [], total: 0 };

const APPROVALS: ReviewListTarget = {
	list: "feedback",
	search: { deliveryState: ["AWAITING_APPROVAL"] },
};
const FAILED_DELIVERIES: ReviewListTarget = {
	list: "feedback",
	search: { ...reviewOverviewScope, deliveryState: ["FAILED", "PARTIALLY_FAILED"] },
};
const FAILED_REVIEWS: ReviewListTarget = {
	list: "runs",
	search: { ...reviewOverviewScope, status: ["FAILED", "TIMED_OUT"] },
};

/**
 * What an admin owes the reviews: decisions first, then failures. A group with nothing in it is left
 * out, and with nothing anywhere one line says so — so an empty section never reads as broken, and a
 * group still loading is never mistaken for one with nothing in it.
 */
const meta = {
	component: ReviewAttention,
	parameters: { layout: "padded" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		// Twelve waiting, three shown: the group says how many and links to the rest.
		approvals: { state: ready(awaitingApprovalFeedback, 12), list: APPROVALS },
		failedDeliveries: { state: ready(failedFeedback), list: FAILED_DELIVERIES },
		failedReviews: { state: ready(failedReviewRuns), list: FAILED_REVIEWS },
		rangeInSentence: "the last 30 days",
	},
	argTypes: {
		approvals: { control: false },
		failedDeliveries: { control: false },
		failedReviews: { control: false },
	},
} satisfies Meta<typeof ReviewAttention>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "Needs you", level: 2 });
		// Decisions first, failures next, each group titled with its whole count.
		await expect(
			canvas.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent),
		).toEqual(["Awaiting your approval 12", "Failed to deliver 1", "Failed reviews 1"]);
		const approvals = within(canvas.getByRole("list", { name: "Awaiting your approval" }));
		await expect(approvals.getAllByRole("listitem")).toHaveLength(3);
		// Only a group showing less than it counts links on to the rest.
		const all = new URL(canvas.getByRole<HTMLAnchorElement>("link", { name: "See all 12" }).href);
		await expect(all.pathname).toBe("/w/demo/admin/practices/reviews/feedback");
		await expect(all.searchParams.get("deliveryState")).toBe('["AWAITING_APPROVAL"]');
		await expect(canvas.getAllByRole("link", { name: /^See all/u })).toHaveLength(1);
		// A row opens its record over the page.
		const [firstApproval] = awaitingApprovalFeedback;
		const [firstLink] = approvals.getAllByRole("link");
		if (!firstApproval || !firstLink) {
			throw new Error("The fixtures hold no feedback awaiting approval");
		}
		await expect(levelsOpenedBy(firstLink)).toEqual([`feedback:${firstApproval.id}`]);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("list", { name: "Awaiting your approval" });
		await expectNoPageOverflow();
	},
};

/** Nothing is owed: one line says so, in the range the failures were counted over. */
export const AllClear: Story = {
	args: {
		approvals: { state: NOTHING, list: APPROVALS },
		failedDeliveries: { state: NOTHING, list: FAILED_DELIVERIES },
		failedReviews: { state: NOTHING, list: FAILED_REVIEWS },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No feedback awaits your approval, and nothing failed in the last 30 days."),
		).toBeVisible();
		await expect(canvas.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
	},
};

/** A group with nothing in it is left out rather than drawn empty. */
export const OnlyDecisionsOwed: Story = {
	args: {
		failedDeliveries: { state: NOTHING, list: FAILED_DELIVERIES },
		failedReviews: { state: NOTHING, list: FAILED_REVIEWS },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent),
		).toEqual(["Awaiting your approval 12"]);
		await expect(canvas.queryByText(/nothing failed/u)).not.toBeInTheDocument();
	},
};

/**
 * Still loading is not the same as nothing owed: one skeleton stands for the section until every
 * group has answered, so neither a group nor the all-clear line appears only to be taken back.
 */
export const Loading: Story = {
	args: {
		failedReviews: { state: { status: "loading" }, list: FAILED_REVIEWS },
	},
	play: async ({ canvas }) => {
		canvas.getByText("Loading what needs you");
		await expect(canvas.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
		await expect(canvas.queryByText(/No feedback awaits your approval/u)).not.toBeInTheDocument();
	},
};

/** One group failing costs that group, and is never read as "nothing failed". */
export const OneGroupFailed: Story = {
	args: {
		failedReviews: {
			state: {
				status: "error",
				error: { status: 503, detail: "The review index is unavailable." },
				onRetry: fn(),
			},
			list: FAILED_REVIEWS,
		},
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Couldn't load failed reviews")).toBeVisible();
		canvas.getByRole("list", { name: "Awaiting your approval" });
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		const { state } = args.failedReviews;
		if (state.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(state.onRetry).toHaveBeenCalledOnce();
	},
};
