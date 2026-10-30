import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { ReviewOverview } from "@/api/types.gen";
import type { ReviewRange } from "@/components/admin/practice-reviews/review-range";
import { ReviewReadinessBanner } from "@/components/admin/practices/review/ReviewReadinessBanner";
import { forgetSectionFold } from "@/components/layout/section-folds";
import { Button } from "@/components/ui/button";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { minutesBefore } from "@/stories/story-clock";
import { precedes } from "@/test/dom";

import {
	busyReviewQueue,
	deliveredCounts,
	feedbackCounts,
	awaitingApprovalFeedback,
	practiceReviewOverview,
	practiceReviewTiming,
	readyReviewOverview,
	reviewOverviewScope,
	withheldByReasonCounts,
	workspacePractices,
} from "./fixtures";
import {
	PracticeReviewOverviewPage,
	type PracticeReviewOverviewPageProps,
} from "./PracticeReviewOverviewPage";
import type { ReviewOverviewState, ReviewSectionState } from "./review-states";

function ready<T>(items: T[], total = items.length): ReviewSectionState<T> {
	return { status: "ready", items, total };
}

const RANGE = { from: reviewOverviewScope.from, to: reviewOverviewScope.to };

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
		previous: {
			status: "ready",
			period: { overview: practiceReviewOverview },
		},
		attention: {
			approvals: {
				state: ready(awaitingApprovalFeedback),
			},
		},
		delivery: {
			delivered: { status: "ready", counts: deliveredCounts, stale: false },
			withheld: { status: "ready", counts: withheldByReasonCounts, stale: false },
		},
		timing: { status: "ready", timing: practiceReviewTiming, stale: false },
		queue: { status: "ready", queue: busyReviewQueue },
		practices: workspacePractices,
	},
	argTypes: {
		overview: { control: false },
		timing: { control: false },
		queue: { control: false },
		previous: { control: false },
		attention: { control: false },
		delivery: { control: false },
		banner: { control: false },
	},
	// Controlled: the range comes back through the same prop the choice is reported on.
	render: (args) => (
		<Stateful<ReviewRange> initial={args.range}>
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
		).toEqual(["Needs you", "Queue", "What the reviews did", "Delivery", "Practices"]);
		// The range heads the page, above what it governs and what it does not.
		const rangeControl = canvas.getByRole("button", { name: "30 days" });
		await expect(precedes(rangeControl, canvas.getByRole("heading", { name: "Needs you" }))).toBe(
			true,
		);
		canvas.getByRole("link", { name: /^Review \d+ (?:piece|pieces) of feedback$/u });
		// What failed is counted where it happened, over the range, not in a line under Needs you.
		await expect(canvas.queryByRole("button", { name: /^Why last/u })).not.toBeInTheDocument();
		const failed = new URL(
			canvas.getByRole<HTMLAnchorElement>("link", {
				name: "9% failed, 3 of 35 finished reviews",
			}).href,
		);
		await expect(failed.searchParams.getAll("status")).toStrictEqual(["FAILED"]);
		await expect(failed.searchParams.get("from")).toBe(RANGE.from);
		canvas.getByRole("list", { name: "Stages" });
		// A header row, five practices and the totals; the sixth, not checked, is no row and no line.
		const practices = within(canvas.getByRole("region", { name: "Practices" }));
		await expect(practices.getAllByRole("row")).toHaveLength(7);
		const queue = within(canvas.getByRole("region", { name: "Queue" }));
		await expect(
			canvas.getByRole("region", { name: "Queue" }).querySelectorAll("[data-slot=card]"),
		).toHaveLength(1);
		const queueNow = within(queue.getByRole("region", { name: "The queue" }));
		const reviewTime = within(queue.getByRole("region", { name: "Review time" }));
		await expect(canvas.getAllByRole("region", { name: "The queue" })).toHaveLength(1);
		// What waits and runs is now; what finished follows the range, as everything else in the area.
		queueNow.getByText("Now");
		reviewTime.getByText("Last 30 days");
		reviewTime.getByRole("link", { name: "35 reviews finished" });
		const stages = canvas.getByRole("list", { name: "Stages" }).querySelectorAll(":scope > li");
		await expect([...stages].map((card) => card.textContent)).toEqual([
			expect.stringMatching(/^Reviews/u),
			expect.stringMatching(/^Observations/u),
		]);
		const delivery = canvas.getByRole("list", { name: "Delivery" }).querySelectorAll(":scope > li");
		await expect(delivery).toHaveLength(2);
		await expect(delivery[0]).toHaveTextContent(/^Feedback/u);
		await expect(delivery[1]).toHaveTextContent(/^Delivered and withheld/u);
		await expect(delivery[1]?.querySelector("[role=group][aria-label=Failures]")).toHaveTextContent(
			/^Feedback failed to deliver · 1 of 16 sent6%/u,
		);
		// Awaiting approval is counted under Delivery; the way to approve stays under Needs you.
		await expect(canvas.getByRole("list", { name: "Feedback by delivery" })).toHaveTextContent(
			"3 awaiting approval",
		);
		// Every count of the Feedback tile opens the Feedback table filtered to it, over the range.
		const feedbackLegend = within(canvas.getByRole("list", { name: "Feedback by delivery" }));
		const opens = (name: string) => {
			const url = new URL(feedbackLegend.getByRole<HTMLAnchorElement>("link", { name }).href);
			return [
				url.pathname,
				url.searchParams.getAll("state").join(","),
				url.searchParams.get("from"),
				url.searchParams.get("to"),
			];
		};
		await expect(opens("3 awaiting approval")).toStrictEqual([
			"/w/demo/admin/practices/reviews/feedback",
			"AWAITING_APPROVAL",
			RANGE.from,
			RANGE.to,
		]);
		await expect(opens("1 ready")[1]).toBe("READY");
		await expect(opens("14 delivered")[1]).toBe("DELIVERED");
		await expect(opens("1 delivery unconfirmed")[1]).toBe("UNCONFIRMED");
		await expect(opens("4 withheld")[1]).toBe("WITHHELD,REJECTED");
		await expect(opens("1 failed to deliver")[1]).toBe("FAILED");
		await expect(opens("1 replaced by newer")[1]).toBe("REPLACED");
		const resultsLegend = canvas.getByRole("list", { name: "Observations by outcome" });
		await expect(resultsLegend).toHaveTextContent(
			"35 met27 not met10 does not apply6 could not determine",
		);
		const checked = canvas.getByRole("list", { name: "Observations checked by an admin" });
		await expect(checked.parentElement).toBe(resultsLegend.parentElement);
		await expect(checked.querySelector("svg")).toHaveClass("text-muted-foreground");
		const resultLink = (name: string) =>
			new URL(canvas.getByRole<HTMLAnchorElement>("link", { name }).href);
		await expect(resultLink("27 not met").searchParams.getAll("outcome")).toStrictEqual([
			"NOT_MET",
		]);
		await expect(resultLink("3 marked incorrect").searchParams.get("markedIncorrect")).toBe("true");
		await expect(resultLink("3 marked incorrect").pathname).toBe(
			"/w/demo/admin/practices/reviews/results",
		);
		// The Reviews tile alike: each outcome, and what is not finished yet, open the Reviews table.
		await expect(resultLink("3 failed").searchParams.getAll("status")).toStrictEqual(["FAILED"]);
		await expect(resultLink("3 not finished yet").searchParams.getAll("status")).toStrictEqual([
			"QUEUED",
			"RUNNING",
		]);
		await expect(canvas.queryByText(/recorded nothing/u)).not.toBeInTheDocument();

		// The range belongs to the whole page: the practices' sentence follows it.
		canvas.getByText(
			"How the reviews judged each practice in the last 30 days, the ones that need a look first.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "90 days" }));
		await expect(args.onRangeChange).toHaveBeenCalledWith("90d");
		canvas.getByText(
			"How the reviews judged each practice in the last 90 days, the ones that need a look first.",
		);
	},
};

/**
 * Today is a range of its own, so the day's reviews are counted as they arrive and set against
 * yesterday. The rates under Delivery and the practices' sentence follow it.
 */
export const Today: Story = {
	args: {
		range: "today",
		previous: {
			status: "ready",
			period: { overview: practiceReviewOverview },
		},
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("button", { name: "Today" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		canvas.getByText(
			"How the reviews judged each practice today, the ones that need a look first.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "7 days" }));
		await expect(args.onRangeChange).toHaveBeenCalledWith("7d");
		canvas.getByText(
			"How the reviews judged each practice in the last 7 days, the ones that need a look first.",
		);
	},
};

/**
 * All time in place of 12 months: every review since the workspace's first. There is no period
 * before it, so no total is set against anything, and the lists its counts open have no days.
 */
export const AllTime: Story = {
	args: {
		range: "all",
		scope: {},
		previous: { status: "none" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "All time" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.queryByRole("button", { name: "12 months" })).not.toBeInTheDocument();
		await expect(canvas.queryByText(/previous/u)).not.toBeInTheDocument();
		canvas.getByText(
			"How the reviews judged each practice over all time, the ones that need a look first.",
		);
		const delivered = new URL(
			canvas.getByRole<HTMLAnchorElement>("link", { name: "14 delivered" }).href,
		);
		await expect(delivered.searchParams.has("from")).toBe(false);
		// No list a count opens carries days.
		const withDays = canvas
			.getAllByRole<HTMLAnchorElement>("link")
			.filter((link) => new URL(link.href).searchParams.has("from"))
			.map((link) => link.textContent);
		await expect(withDays).toStrictEqual([]);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "Practices", level: 2 });
		await expectNoPageOverflow();
	},
};

const COURSE_DAY: ReviewOverview = {
	...practiceReviewOverview,
	feedback: {
		...feedbackCounts([]),
		delivered: 60,
		ready: 5,
		failed: 2,
		withheld: 30,
		rejected: 3,
		replaced: 20,
	},
	practices: [
		{
			feedback: feedbackCounts([]),
			group: null,
			practiceSlug: "thin-controllers",
			practiceName: "Wiki pages are kept up to date",
			results: { met: 0, notMet: 0, doesNotApply: 29, couldNotDetermine: 1 },
			resultsMarkedIncorrect: 0,
		},
		...practiceReviewOverview.practices.filter(
			(practice) => practice.practiceSlug !== "thin-controllers",
		),
	],
};

export const NeedsAttention: Story = {
	args: {
		queue: {
			status: "ready",
			queue: { ...busyReviewQueue, waiting: 14, oldestWaitingSince: minutesBefore(42) },
		},
		overview: readyReviewOverview(COURSE_DAY),
		timing: {
			status: "ready",
			timing: {
				...practiceReviewTiming,
				total: { count: 140, medianSeconds: 25 * 60, p90Seconds: 3 * 3600 + 54 * 60 },
				feedbackWithheldAsMerged: 21,
			},
			stale: false,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Mostly does not apply");
	},
};

/**
 * Reviews cannot run, so the page says so first; the route passes the banner only while something
 * stops them, so a working workspace is not told so on every visit.
 */
export const ReviewsCannotStart: Story = {
	args: {
		banner: <ReviewReadinessBanner readiness={{ enabled: true, model: { status: "ready" } }} />,
	},
	play: async ({ canvas }) => {
		const banner = canvas.getByText("Reviews cannot start");
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
		const practices = within(canvas.getByRole("region", { name: "Practices" }));
		await expect(practices.getByRole("table")).toHaveAttribute("aria-busy", "true");
		await expect(await canvas.findByText("Updating…", undefined, { timeout: 3000 })).toBeVisible();
	},
};

/**
 * One read feeds the stages, Delivery and the practices, so its failure is said once, with one
 * retry, and Delivery and the practices are left out rather than failing a second time. What is owed still answers.
 */
export const OverviewFailed: Story = {
	args: {
		overview: {
			status: "error",
			error: { status: 500 },
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("We could not load what the reviews did");
		await expect(canvas.queryByRole("heading", { name: "Practices" })).not.toBeInTheDocument();
		// Delivery counts from the same overview, so its failure is not said a second time.
		await expect(canvas.queryByRole("heading", { name: "Delivery" })).not.toBeInTheDocument();
		canvas.getByRole("link", { name: /^Review \d+ (?:piece|pieces) of feedback$/u });
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

const FOLDABLE = ["queue", "what-the-reviews-did", "delivery", "practices"].map(
	(part) => `practice-reviews-overview:${part}`,
);

function forgetFoldable() {
	for (const key of FOLDABLE) {
		forgetSectionFold(key);
	}
}

/**
 * The overview's long sections fold, one choice per viewer and section; "Needs you" never does, since
 * it is what an admin must not miss.
 */
export const FoldsItsLongSections: Story = {
	beforeEach: () => {
		forgetFoldable();
		return forgetFoldable;
	},
	play: async ({ canvas, userEvent }) => {
		for (const title of ["Queue", "What the reviews did", "Delivery", "Practices"]) {
			await expect(canvas.getByRole("button", { name: title })).toHaveAttribute(
				"aria-expanded",
				"true",
			);
		}
		await expect(canvas.queryByRole("button", { name: "Needs you" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Delivery" }));
		await expect(canvas.getByRole("button", { name: "Delivery" })).toHaveAttribute(
			"aria-expanded",
			"false",
		);
		await expect(canvas.queryByRole("region", { name: "Delivered and withheld" })).toBeNull();
		// The other sections stay as they were.
		await expect(canvas.getByRole("button", { name: "Practices" })).toHaveAttribute(
			"aria-expanded",
			"true",
		);
	},
};

const FAILED_OVERVIEW = {
	status: "error",
	error: { status: 500 },
	onRetry: fn(),
} satisfies ReviewOverviewState;

/** The overview read failing and coming back while the page stays open, driven by story-only buttons. */
function OverviewReadChanges(args: PracticeReviewOverviewPageProps) {
	return (
		<Stateful initial={args.overview}>
			{(overview, setOverview) => (
				<>
					<PracticeReviewOverviewPage {...args} overview={overview} />
					<div className="mt-10 flex flex-wrap gap-2">
						<Button type="button" variant="outline" onClick={() => setOverview(FAILED_OVERVIEW)}>
							Make the overview read fail
						</Button>
						<Button type="button" variant="outline" onClick={() => setOverview(args.overview)}>
							Make the overview read succeed
						</Button>
					</div>
				</>
			)}
		</Stateful>
	);
}

/**
 * The overview's failure is the only thing said for the stages, Delivery and the practices, so a
 * fold the reader chose for What the reviews did never hides it or its retry. The fold stays the
 * reader's: once the overview is back, the section is still folded until they open it.
 */
export const FoldedOverviewStillSaysItFailed: Story = {
	beforeEach: () => {
		forgetFoldable();
		return forgetFoldable;
	},
	render: (args) => <OverviewReadChanges {...args} />,
	play: async ({ canvas, userEvent }) => {
		const whatTheReviewsDid = () => canvas.getByRole("button", { name: "What the reviews did" });
		await userEvent.click(whatTheReviewsDid());
		await expect(whatTheReviewsDid()).toHaveAttribute("aria-expanded", "false");
		await expect(canvas.queryByRole("list", { name: "Stages" })).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "Make the overview read fail" }));
		await expect(canvas.queryByRole("heading", { name: "Delivery" })).toBeNull();
		await expect(canvas.queryByRole("heading", { name: "Practices" })).toBeNull();
		await expect(canvas.getByText("We could not load what the reviews did")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();

		await userEvent.click(canvas.getByRole("button", { name: "Make the overview read succeed" }));
		await expect(whatTheReviewsDid()).toHaveAttribute("aria-expanded", "false");
		await expect(canvas.queryByRole("list", { name: "Stages" })).toBeNull();
		await expect(canvas.queryByText("We could not load what the reviews did")).toBeNull();

		await userEvent.click(whatTheReviewsDid());
		await expect(whatTheReviewsDid()).toHaveAttribute("aria-expanded", "true");
		await expect(canvas.getByRole("list", { name: "Stages" })).toBeVisible();
	},
};
