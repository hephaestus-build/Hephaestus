import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import type { ActivityRange } from "@/components/activity/activity-range";
import { ReviewRunningBanner } from "@/components/admin/practices/review/ReviewRunningBanner";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { precedes } from "@/test/dom";

import {
	awaitingApprovalFeedback,
	failedFeedback,
	failedReviewRuns,
	practiceReviewOverview,
	readyReviewOverview,
	reviewOverviewScope,
} from "./fixtures";
import { PracticeReviewOverviewPage } from "./PracticeReviewOverviewPage";
import type { ReviewSectionState } from "./review-states";

function ready<T>(items: T[]): ReviewSectionState<T> {
	return { status: "ready", items, total: items.length };
}

/**
 * The home of Practice reviews, in the order an admin's questions come: can reviews run, what do I
 * owe them, what did they do, and which practices did it. The range is the page's; the decisions
 * owed have none, because a decision does not expire with the range. Each region's own empty,
 * loading and stale states are in its own stories.
 */
const meta = {
	component: PracticeReviewOverviewPage,
	parameters: { layout: "fullscreen" },
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		range: "30d",
		onRangeChange: fn(),
		scope: reviewOverviewScope,
		overview: readyReviewOverview(),
		attention: {
			approvals: {
				state: ready(awaitingApprovalFeedback),
				list: { list: "feedback", search: { deliveryState: ["AWAITING_APPROVAL"] } },
			},
			failedDeliveries: {
				state: ready(failedFeedback),
				list: { list: "feedback", search: { ...reviewOverviewScope, deliveryState: ["FAILED"] } },
			},
			failedReviews: {
				state: ready(failedReviewRuns),
				list: { list: "runs", search: { ...reviewOverviewScope, status: ["FAILED", "TIMED_OUT"] } },
			},
		},
	},
	argTypes: {
		overview: { control: false },
		attention: { control: false },
		banner: { control: false },
	},
	// Controlled: the range comes back through the same prop the choice is reported on.
	render: (args) => (
		<Stateful<ActivityRange> initial={args.range}>
			{(range, setRange) => (
				<PracticeReviewOverviewPage
					{...args}
					range={range}
					onRangeChange={(next) => {
						setRange(next);
						args.onRangeChange(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeReviewOverviewPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(
			canvas.getAllByRole("heading", { level: 2 }).map((heading) => heading.textContent),
		).toEqual(["Needs you", "What the reviews did", "Practices"]);
		canvas.getByRole("list", { name: "Awaiting your approval" });
		canvas.getByText(/^In this range: 38 reviews, 78 observations/u);
		// A header row and five practices: the sixth was not checked, so it is not ranked at all.
		await expect(canvas.getAllByRole("row")).toHaveLength(6);

		// The range belongs to the whole page: the practices' sentence follows it.
		canvas.getByText("How the reviews judged each practice in the last 30 days, busiest first.");
		await userEvent.click(canvas.getByRole("button", { name: "90 days" }));
		await expect(args.onRangeChange).toHaveBeenCalledWith("90d");
		canvas.getByText("How the reviews judged each practice in the last 90 days, busiest first.");
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "Practices", level: 2 });
		await expectNoPageOverflow();
	},
};

/**
 * Reviews cannot run, so the page says so first; the route passes the banner only while something
 * stops them, so a working workspace is not told so on every visit.
 */
export const ReviewsCannotStart: Story = {
	args: {
		banner: <ReviewRunningBanner running={{ enabled: true, model: { status: "ready" } }} />,
	},
	play: async ({ canvas }) => {
		const banner = canvas.getByText("Reviews can't start");
		await expect(precedes(banner, canvas.getByRole("heading", { name: "Needs you" }))).toBe(true);
	},
};

/**
 * A range just chosen is loading: the previous range's figures stand in, drained of colour and
 * marked busy, and after a moment the range control says "Updating…" where the reader pressed.
 */
export const RangeUpdating: Story = {
	args: { overview: { status: "ready", overview: practiceReviewOverview, stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Stages" })).toHaveAttribute("aria-busy", "true");
		await expect(canvas.getByRole("table")).toHaveAttribute("aria-busy", "true");
		await expect(await canvas.findByText("Updating…", undefined, { timeout: 3000 })).toBeVisible();
	},
};

/**
 * One read feeds both the stages and the practices, so its failure is said once, with one retry,
 * and the practices are left out rather than failing a second time. What is owed still answers.
 */
export const OverviewFailed: Story = {
	args: {
		overview: {
			status: "error",
			error: { status: 500, detail: "Something went wrong." },
			onRetry: fn(),
		},
	},
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("Couldn't load what the reviews did");
		await expect(canvas.queryByRole("heading", { name: "Practices" })).not.toBeInTheDocument();
		canvas.getByRole("list", { name: "Awaiting your approval" });
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.overview.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(args.overview.onRetry).toHaveBeenCalledOnce();
	},
};
