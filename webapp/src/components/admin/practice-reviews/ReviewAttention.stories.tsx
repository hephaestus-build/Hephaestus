import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import { awaitingApprovalFeedback, reviewOverviewScope } from "./fixtures";
import type { ReviewListTarget } from "./review-outcomes";
import type { ReviewCountState, ReviewSectionState } from "./review-states";
import { ReviewAttention } from "./ReviewAttention";

function ready<T>(items: T[], total = items.length): ReviewSectionState<T> {
	return { status: "ready", items, total };
}

function counted(total: number, stale = false): ReviewCountState {
	return { status: "ready", total, stale };
}

const NOTHING = counted(0);

const RANGE = { from: reviewOverviewScope.from, to: reviewOverviewScope.to };

const APPROVALS: ReviewListTarget = {
	list: "feedback",
	search: { deliveryState: ["AWAITING_APPROVAL"], order: "OLDEST" },
};
const FAILED_REVIEWS: ReviewListTarget = {
	list: "runs",
	search: { ...RANGE, status: ["FAILED", "TIMED_OUT"] },
};
const UNPROCESSED_RESULTS: ReviewListTarget = {
	list: "runs",
	search: { ...RANGE, resultProcessing: ["FAILED"] },
};
const FAILED_DELIVERIES: ReviewListTarget = {
	list: "feedback",
	search: { ...RANGE, deliveryState: ["FAILED", "PARTIALLY_FAILED"] },
};

/**
 * Only what asks something of an admin. Decisions come first, as rows and a way to work through
 * them oldest first; what broke in the range is one line of totals, each opening its list. With
 * nothing owed one line says so — so an empty section never reads as broken, and a part still
 * loading is never mistaken for one with nothing in it.
 */
const meta = {
	component: ReviewAttention,
	parameters: { layout: "padded" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		// Twelve waiting, three shown: the heading says how many and links to the rest.
		approvals: { state: ready(awaitingApprovalFeedback, 12), list: APPROVALS },
		failedReviews: { state: counted(2), list: FAILED_REVIEWS },
		unprocessedResults: { state: counted(1), list: UNPROCESSED_RESULTS },
		failedDeliveries: { state: counted(1), list: FAILED_DELIVERIES },
		rangeInSentence: "the last 30 days",
	},
	argTypes: {
		approvals: { control: false },
		failedReviews: { control: false },
		unprocessedResults: { control: false },
		failedDeliveries: { control: false },
	},
} satisfies Meta<typeof ReviewAttention>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "Needs you", level: 2 });
		await expect(
			canvas.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent),
		).toEqual(["Awaiting your approval 12"]);
		const approvals = within(canvas.getByRole("list", { name: "Awaiting your approval" }));
		await expect(approvals.getAllByRole("listitem")).toHaveLength(3);
		// The list the heading counts, oldest first, as the queue is worked through.
		const all = new URL(canvas.getByRole<HTMLAnchorElement>("link", { name: "See all 12" }).href);
		await expect(all.pathname).toBe("/w/demo/admin/practices/reviews/feedback");
		await expect(all.searchParams.get("deliveryState")).toBe('["AWAITING_APPROVAL"]');
		await expect(all.searchParams.get("order")).toBe("OLDEST");
		// The primary action opens the oldest, whose level walks the rest.
		const [oldest] = awaitingApprovalFeedback;
		if (!oldest) {
			throw new Error("The fixtures hold no feedback awaiting approval");
		}
		const queue = canvas.getByRole("link", { name: "Review 12 pieces of feedback" });
		await expect(levelsOpenedBy(queue)).toEqual([`feedback:${oldest.id}`]);
		// Opened as the queue, which the same level opened from a row is not.
		await expect(
			new URL(String(queue.getAttribute("href")), window.location.origin).searchParams.get("queue"),
		).toBe("approvals");
		// What failed is one line of totals, led by the range they were counted over, each opening
		// exactly the rows it counts.
		await expect(canvas.getByText(/^In the last 30 days:/u).closest("p")).not.toHaveAttribute(
			"aria-busy",
		);
		const failedReviews = new URL(
			canvas.getByRole<HTMLAnchorElement>("link", { name: "2 reviews failed or timed out" }).href,
		);
		await expect(failedReviews.pathname).toBe("/w/demo/admin/practices/reviews/runs");
		await expect(failedReviews.searchParams.get("status")).toBe('["FAILED","TIMED_OUT"]');
		await expect(failedReviews.searchParams.get("from")).toBe(reviewOverviewScope.from);
		const unprocessed = new URL(
			canvas.getByRole<HTMLAnchorElement>("link", {
				name: "1 review's results could not be processed or delivered",
			}).href,
		);
		await expect(unprocessed.searchParams.get("resultProcessing")).toBe('["FAILED"]');
		const failedDeliveries = new URL(
			canvas.getByRole<HTMLAnchorElement>("link", {
				name: "1 piece of feedback failed to deliver",
			}).href,
		);
		await expect(failedDeliveries.pathname).toBe("/w/demo/admin/practices/reviews/feedback");
		await expect(failedDeliveries.searchParams.get("deliveryState")).toBe(
			'["FAILED","PARTIALLY_FAILED"]',
		);
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
		approvals: { state: ready([], 0), list: APPROVALS },
		failedReviews: { state: NOTHING, list: FAILED_REVIEWS },
		unprocessedResults: { state: NOTHING, list: UNPROCESSED_RESULTS },
		failedDeliveries: { state: NOTHING, list: FAILED_DELIVERIES },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No feedback awaits your approval, and nothing failed in the last 30 days."),
		).toBeVisible();
		await expect(canvas.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
	},
};

/** A part with nothing in it is left out of the line rather than read as "0 …". */
export const OnlyDecisionsOwed: Story = {
	args: {
		failedReviews: { state: NOTHING, list: FAILED_REVIEWS },
		unprocessedResults: { state: NOTHING, list: UNPROCESSED_RESULTS },
		failedDeliveries: { state: NOTHING, list: FAILED_DELIVERIES },
	},
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "Awaiting your approval 12", level: 3 });
		await expect(canvas.queryByText(/failed|could not be processed/u)).not.toBeInTheDocument();
	},
};

/** Every piece of feedback awaiting approval is shown, so there is no list to link on to. */
export const FewApprovals: Story = {
	args: {
		approvals: { state: ready(awaitingApprovalFeedback), list: APPROVALS },
		unprocessedResults: { state: NOTHING, list: UNPROCESSED_RESULTS },
	},
	play: async ({ canvas }) => {
		canvas.getByRole("link", { name: "Review 3 pieces of feedback" });
		await expect(canvas.queryByRole("link", { name: /^See all/u })).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("link", { name: /could not be processed/u }),
		).not.toBeInTheDocument();
	},
};

/** Only failures: no approvals heading, and the line alone. */
export const OnlyFailures: Story = {
	args: { approvals: { state: ready([], 0), list: APPROVALS } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
		canvas.getByRole("link", { name: "2 reviews failed or timed out" });
	},
};

/**
 * Still loading is not the same as nothing owed: one skeleton stands for the section until every
 * part has answered, so neither a part nor the all-clear line appears only to be taken back.
 */
export const Loading: Story = {
	args: { failedReviews: { state: { status: "loading" }, list: FAILED_REVIEWS } },
	play: async ({ canvas }) => {
		canvas.getByText("Loading what needs you");
		await expect(canvas.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
		await expect(canvas.queryByText(/No feedback awaits your approval/u)).not.toBeInTheDocument();
	},
};

/** One part failing costs that part, and is never read as "nothing failed". */
export const OnePartFailed: Story = {
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
		canvas.getByRole("link", { name: "1 piece of feedback failed to deliver" });
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		const { state } = args.failedReviews;
		if (state.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(state.onRetry).toHaveBeenCalledOnce();
	},
};

/**
 * The range just changed: the previous range's totals stand in for the new one's, drained of colour
 * and marked busy, so they are never read as the new range's before they are.
 */
export const StaleFailures: Story = {
	args: {
		failedReviews: { state: counted(2, true), list: FAILED_REVIEWS },
		unprocessedResults: { state: counted(1, true), list: UNPROCESSED_RESULTS },
		failedDeliveries: { state: counted(1, true), list: FAILED_DELIVERIES },
	},
	play: async ({ canvas }) => {
		const line = canvas.getByText(/^In the last 30 days:/u).closest("p");
		await expect(line).toHaveAttribute("aria-busy", "true");
		canvas.getByRole("link", { name: "2 reviews failed or timed out" });
	},
};

/**
 * The range just changed and the previous one had nothing failed: that says nothing about the range
 * just chosen, so the section waits rather than declaring it clear.
 */
export const StaleAllClear: Story = {
	args: {
		approvals: { state: ready([], 0), list: APPROVALS },
		failedReviews: { state: counted(0, true), list: FAILED_REVIEWS },
		unprocessedResults: { state: counted(0, true), list: UNPROCESSED_RESULTS },
		failedDeliveries: { state: counted(0, true), list: FAILED_DELIVERIES },
	},
	play: async ({ canvas }) => {
		canvas.getByText("Loading what needs you");
		await expect(canvas.queryByText(/No feedback awaits your approval/u)).not.toBeInTheDocument();
	},
};
