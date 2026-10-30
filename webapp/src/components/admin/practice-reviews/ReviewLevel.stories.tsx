import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { PracticeScriptRun, ReviewDetail, ReviewJob } from "@/api/types.gen";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { eventLabel } from "@/components/practice-trace/fixtures";
import { ReviewRequestedNotice } from "@/components/practice-trace/ReviewRequestedNotice";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import {
	expectSectionHeadings,
	expectSectionLines,
	expectSectionsFold,
} from "@/stories/section-lines";
import { minutesBefore } from "@/stories/story-clock";
import { levelsOpenedBy, listOpenedBy } from "@/test/detail-stack";

import {
	reviewDetailBackingOff,
	reviewDetailCancelledOnBudget,
	reviewDetailCompleted,
	reviewDetailExpiredRuntime,
	reviewDetailPreparationFailed,
	reviewDetailProcessingFailed,
	reviewDetailQueued,
	reviewDetailRunning,
	reviewDetailScanManyJobs,
	reviewDetailTimedOut,
	reviewFeedbackChecksOf,
	reviewPracticeScripts,
	reviewUsageTwoAttempts,
	reviewUsageUnpriced,
	resultCounts,
} from "./fixtures";
import { forgetLevelFolds } from "./level-folds";
import { SHEET_SECTIONS } from "./RecordSheet";
import { REQUEST_BLOCKED } from "./RequestReviewAction";
import { reviewLevel } from "./review-levels";
import { ReviewLevel, type ReviewLevelProps, RUNTIME_EXPIRED } from "./ReviewLevel";

const ready = (review: ReviewDetail): ReviewLevelProps["review"] => ({ status: "ready", review });

const REQUESTED_REVIEW_ID = "0f2b7c1e-9a3d-4c5b-8e1f-2d6a7b8c9d01";

/** The value beside one of the sheet's fact labels. */
const factValue = (panel: HTMLElement, label: string) =>
	within(panel).getByText(label, { selector: "dt" }).nextElementSibling;

const SCRIPTS: Record<string, PracticeScriptRun> = {
	"thin-controllers": reviewPracticeScripts.fromCode,
	"errors-carry-context": reviewPracticeScripts.withModels,
	"product-language": reviewPracticeScripts.withheld,
};

const withScripts: ReviewDetail = {
	...reviewDetailCompleted,
	practices: reviewDetailCompleted.practices.map((practice) => ({
		...practice,
		script: SCRIPTS[practice.practiceSlug] ?? null,
	})),
};

const meta = {
	component: ReviewLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		review: ready(reviewDetailCompleted),
		eventLabel,
		feedbackChecks: { status: "ready", checks: [] },
		onCancel: fn(),
		cancelPending: false,
		onRequestReview: fn(),
	},
	argTypes: { path: { control: false }, eventLabel: { control: false } },
	render: (args) => (
		<InLevelStack
			entry={reviewLevel(
				args.review.status === "ready" ? args.review.review.id : reviewDetailCompleted.id,
			)}
			path={args.path}
		>
			{(level) => <ReviewLevel {...args} {...level} />}
		</InLevelStack>
	),
	// A fold is kept in this browser, so every story starts with all sections open.
	beforeEach: () => {
		forgetLevelFolds("review");
		return () => forgetLevelFolds("review");
	},
} satisfies Meta<typeof ReviewLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, userEvent }) => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		// The fixed order of every sheet.
		const headings = panel
			.getAllByRole("heading", { level: 3 })
			.map((heading) => heading.textContent);
		await expect(headings).toStrictEqual([
			SHEET_SECTIONS.what,
			SHEET_SECTIONS.state,
			"Every practice",
			SHEET_SECTIONS.technical,
		]);
		// One line between each two sections, one section size, and every section folds.
		await expectSectionLines(panelElement, 3);
		await expectSectionHeadings(panelElement);
		await expectSectionsFold(panelElement, userEvent);
		// Status, time from request and the finishing time once, in the status line.
		const status = panelElement.querySelector("[data-sheet-status]");
		await expect(status).toHaveTextContent(/Complete.*5 min from request.*finished/u);
		// The first attempt still holds its own start, so the queue and run stay split.
		const history = within(panel.getByRole("list", { name: "History" }));
		history.getByText("Asked for");
		history.getByText("Started");
		history.getByText("after 40 s in the queue");
		history.getByText("5 min after it was asked for");
		panel.getByText("Started by");
		panel.getByText(/Marked ready for review/u);
		panel.getByText("Review ID");
		// Inside Practice reviews the sheet links only its own lists, scoped to this review.
		for (const link of panel.queryAllByRole("link")) {
			const href = String(link.getAttribute("href"));
			if (href.includes("/admin/practices/reviews")) {
				await expect(href).toContain(`reviewId=${reviewDetailCompleted.id}`);
			}
		}
		await userEvent.click(panel.getByRole("button", { name: "Request another review" }));
		await expect(args.onRequestReview).not.toHaveBeenCalled();
		const confirm = await screen.findByRole("alertdialog");
		await userEvent.click(within(confirm).getByRole("button", { name: "Request review" }));
		await expect(args.onRequestReview).toHaveBeenCalledOnce();
	},
};

export const PracticesWithTheirState: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const list = panel.getByRole("list", { name: "Every practice in this review" });
		const withheld = list.querySelector('[data-practice-status="WITHHELD"]');
		await expect(withheld).not.toBeNull();
		await expect(withheld).toHaveTextContent("Withheld");
		await expect(withheld).toHaveTextContent(/provider did not return them/u);
		const done = list.querySelector('[data-practice-status="DONE"]');
		await expect(done).toHaveTextContent(/Done.*1 met/u);
	},
};

const [firstPractice, secondPractice] = reviewDetailCompleted.practices;
if (firstPractice === undefined || secondPractice === undefined) {
	throw new Error("The completed review must have two practices");
}

/**
 * Each finished practice's counts open the Observations tab at exactly that practice's observations of this
 * review and that outcome, and its problems where it has a Not met result. Each link is named for
 * its practice too, since the same count can stand on several rows.
 */
export const PracticeCountsOpenTheirResults: Story = {
	args: {
		review: ready({
			...reviewDetailCompleted,
			practices: [
				{
					...firstPractice,
					status: "DONE",
					reason: null,
					results: { met: 0, notMet: 2, doesNotApply: 0, couldNotDetermine: 1 },
				},
				{
					...secondPractice,
					status: "DONE",
					reason: null,
					results: { met: 1, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 },
				},
			],
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const list = within(panel.getByRole("list", { name: "Every practice in this review" }));
		const reviewId = reviewDetailCompleted.id;
		for (const [name, practice, outcome] of [
			[`2 not met, ${firstPractice.practiceName}`, firstPractice, "NOT_MET"],
			[
				`1 could not determine, ${firstPractice.practiceName}`,
				firstPractice,
				"COULD_NOT_DETERMINE",
			],
			[`1 met, ${secondPractice.practiceName}`, secondPractice, "MET"],
		] as const) {
			const opened = listOpenedBy(list.getByRole("link", { name }));
			await expect(opened.list).toBe("results");
			await expect(opened.search).toMatchObject({
				reviewId,
				practiceSlug: practice.practiceSlug,
				outcome,
			});
		}
		const problems = listOpenedBy(
			list.getByRole("link", { name: `View problems, ${firstPractice.practiceName}` }),
		);
		await expect(problems.list).toBe("problems");
		await expect(problems.search).toMatchObject({
			reviewId,
			practiceSlug: firstPractice.practiceSlug,
		});
		await expect(
			list.queryByRole("link", { name: `View problems, ${secondPractice.practiceName}` }),
		).toBeNull();
	},
};

export const ResultsRecordedWhileReady: Story = {
	args: {
		review: ready({
			...reviewDetailRunning,
			results: { met: 2, notMet: 1, doesNotApply: 0, couldNotDetermine: 0 },
			practices: [
				{
					...firstPractice,
					status: "READY",
					reason: null,
					script: null,
					results: { met: 1, notMet: 1, doesNotApply: 0, couldNotDetermine: 0 },
				},
				{
					...secondPractice,
					status: "READY",
					reason: null,
					script: null,
					results: { met: 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 },
				},
				...reviewDetailCompleted.practices
					.filter((practice) => practice.status === "WITHHELD")
					.map((practice) => ({
						...practice,
						results: { met: 1, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 },
					})),
			],
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const list = panel.getByRole("list", { name: "Every practice in this review" });
		const [recorded, captured] = list.querySelectorAll('[data-practice-status="READY"]');
		if (!(recorded instanceof HTMLElement) || !(captured instanceof HTMLElement)) {
			throw new Error("Both practices must remain Ready");
		}
		await expect(recorded).toHaveTextContent("Observations recorded so far");
		await expect(recorded).toHaveTextContent("Ready");
		for (const [name, outcome] of [
			["1 met", "MET"],
			["1 not met", "NOT_MET"],
		] as const) {
			const opened = listOpenedBy(
				within(recorded).getByRole("link", { name: `${name}, ${firstPractice.practiceName}` }),
			);
			await expect(opened.list).toBe("results");
			await expect(opened.search).toMatchObject({
				reviewId: reviewDetailRunning.id,
				practiceSlug: firstPractice.practiceSlug,
				outcome,
			});
		}
		const problems = listOpenedBy(
			within(recorded).getByRole("link", { name: `View problems, ${firstPractice.practiceName}` }),
		);
		await expect(problems.list).toBe("problems");
		await expect(problems.search).toMatchObject({
			reviewId: reviewDetailRunning.id,
			practiceSlug: firstPractice.practiceSlug,
		});
		await expect(captured).toHaveTextContent("Everything it reads was captured.");
		await expect(within(captured).queryByRole("link")).toBeNull();
		await expect(captured).not.toHaveTextContent("Observations recorded so far");
		await expect(list).not.toHaveTextContent(/It runs next|Done/u);
		const withheld = list.querySelector('[data-practice-status="WITHHELD"]');
		await expect(withheld).toHaveTextContent(/provider did not return them/u);
		await expect(withheld).toHaveTextContent("1 met");
	},
};

export const ActiveUsageNotRecordedYet: Story = {
	args: {
		review: ready({
			...reviewDetailRunning,
			usage: {
				attempts: [],
				calls: 0,
				inputTokens: 0,
				outputTokens: 0,
				costUsd: "0",
				priced: true,
			},
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		for (const label of [
			"Cost",
			"Review model calls",
			"Tokens read",
			"Tokens written",
			"Cache tokens",
			"Input from cache",
		]) {
			const fact = panel.getByText(label).closest("div");
			await expect(fact).toHaveTextContent("Not recorded yet");
		}
	},
};

/**
 * The review's feedback counts open the Feedback tab at exactly this review's feedback in that
 * delivery family, as its observation counts open the Observations tab. Each count carries its family's mark.
 */
export const FeedbackCountsOpenTheirFeedback: Story = {
	args: {
		review: ready({
			...reviewDetailCompleted,
			feedback: {
				awaitingApproval: 0,
				ready: 0,
				delivered: 2,
				unconfirmed: 0,
				withheld: 1,
				rejected: 1,
				replaced: 0,
				failed: 0,
			},
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const reviewId = reviewDetailCompleted.id;
		for (const [name, state] of [
			["2 delivered", "DELIVERED"],
			["2 withheld", ["WITHHELD", "REJECTED", "REPLACED"]],
		] as const) {
			const link = panel.getByRole("link", { name });
			const opened = listOpenedBy(link);
			await expect(opened.list).toBe("feedback");
			await expect(opened.search).toMatchObject({ reviewId, state });
			await expect(link.parentElement?.querySelector("svg[aria-hidden]")).not.toBeNull();
		}
	},
};

export const Running: Story = {
	args: { review: ready(reviewDetailRunning) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("button", { name: "Cancel review" })).toBeEnabled();
		await expect(panel.queryByRole("button", { name: "Request another review" })).toBeNull();
		// Processing starts when the attempt ends, so a running attempt shows only its own status.
		const execution = within(panel.getByRole("list", { name: "Timing" }));
		execution.getByText("Running");
		await expect(execution.queryByText("Nothing to process")).toBeNull();
	},
};

export const Queued: Story = {
	args: { review: ready(reviewDetailQueued) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent(
			/Queued.*asked for/u,
		);
		within(panelElement).getByText("Practices appear here as the review reaches them.");
		const execution = within(within(panelElement).getByRole("list", { name: "Timing" }));
		execution.getByText("Queued");
		await expect(execution.queryByText("Nothing to process")).toBeNull();
	},
};

export const BackingOff: Story = {
	args: { review: ready(reviewDetailBackingOff) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		within(panel.getByRole("list", { name: "History" })).getByText("Retry due");
		const execution = within(panel.getByRole("list", { name: "Timing" }));
		execution.getByText("1 of 2 · Attempt 1");
		execution.getByText("2 of 2 · Attempt 2");
		execution.getByText(/out of memory/u);
		// The failed attempt ended with nothing to process; the queued retry has not reached processing.
		await expect(execution.getByText("Nothing to process").closest("li")).toHaveTextContent(
			"1 of 2 · Attempt 1",
		);
	},
};

const REQUESTED = minutesBefore(30);
const afterRequest = (seconds: number) => new Date(REQUESTED.getTime() + seconds * 1000);

/**
 * A review whose job continued into a second attempt. The job keeps only that attempt's times, and
 * the stage timing repeats them, so neither holds when the first attempt started.
 */
function continuedReview(status: ReviewDetail["status"], job: Partial<ReviewJob>): ReviewDetail {
	const latest: ReviewJob = {
		id: "0a0a0a0a-1111-4111-8111-000000000101",
		attempt: 1,
		status: "COMPLETED",
		processingStatus: "DONE",
		availableAt: afterRequest(890),
		startedAt: afterRequest(894),
		completedAt: afterRequest(1117),
		exitCode: 0,
		errorMessage: null,
		traceId: "4bf92f3577b34da6a3ce929d0e0e4799",
		...job,
	};
	const done = status !== "RUNNING" && status !== "QUEUED";
	return {
		...reviewDetailCompleted,
		status,
		createdAt: REQUESTED,
		orderedAt: REQUESTED,
		completedAt: done ? afterRequest(1117) : null,
		activeJobCount: done ? 0 : 1,
		runtime: { jobs: [latest] },
		stageTiming: [
			{
				stage: "RUN",
				startedAt: latest.startedAt,
				completedAt: latest.completedAt,
				durationMs:
					latest.startedAt === null || latest.completedAt === null
						? null
						: latest.completedAt.getTime() - latest.startedAt.getTime(),
			},
		],
	};
}

/**
 * Asked for, then the first attempt ran for about a quarter of an hour, and its job continued as a
 * second attempt that started 4 s after it became available. The whole review took 19 min from its
 * request; the 15 min before the second attempt were not a queue, and its 4 min were not the run.
 */
export const ContinuedPastItsFirstAttempt: Story = {
	args: { review: ready(continuedReview("PARTIAL", {})) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		const status = panelElement.querySelector("[data-sheet-status]");
		await expect(status).toHaveTextContent(/Partial.*19 min from request.*finished/u);
		await expect(status).not.toHaveTextContent(/ran |4 min/u);
		const historyList = panel.getByRole("list", { name: "History" });
		const history = within(historyList);
		await expect(history.queryByText("Started")).toBeNull();
		await expect(history.getByText("Latest attempt started").closest("li")).toHaveTextContent(
			"Attempt 2, after 4 s in the queue. When the review first started is no longer recorded.",
		);
		history.getByText("19 min after it was asked for");
		await expect(historyList).not.toHaveTextContent(/15 min|running|Retry due/u);
		const execution = panel.getByRole("list", { name: "Timing" });
		await expect(execution).toHaveTextContent("Attempt 2");
		await expect(execution).toHaveTextContent("Wait to start: 4 seconds · Running: 3 minutes");
	},
};

/**
 * A first attempt keeps its start, but the review finishes a minute after the worker. The history
 * gives the whole elapsed time; Timing gives only the attempt's time.
 */
export const FirstAttemptKeepsItsStart: Story = {
	args: {
		review: ready({
			...continuedReview("COMPLETE", { attempt: 0, availableAt: REQUESTED }),
			completedAt: afterRequest(1177),
		}),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent(
			/Complete.*20 min from request.*finished/u,
		);
		const history = within(panel.getByRole("list", { name: "History" }));
		history.getByText("Started");
		history.getByText("after 15 min in the queue");
		history.getByText("20 min after it was asked for");
		await expect(panel.getByRole("list", { name: "History" })).not.toHaveTextContent(/running/u);
		await expect(panel.getByRole("list", { name: "Timing" })).toHaveTextContent(
			"Running: 3 minutes",
		);
		await expect(history.queryByText("Latest attempt started")).toBeNull();
		await expect(panel.getByRole("list", { name: "Timing" })).toHaveTextContent("Attempt 1");
	},
};

/** The second attempt still runs: it names when that attempt started and claims no end. */
export const ContinuedAndRunning: Story = {
	args: {
		review: ready(
			continuedReview("RUNNING", {
				status: "RUNNING",
				processingStatus: "NONE",
				completedAt: null,
				exitCode: null,
			}),
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		const status = panelElement.querySelector("[data-sheet-status]");
		await expect(status).toHaveTextContent(/Running.*asked for/u);
		await expect(status).not.toHaveTextContent(/from request|started/u);
		const historyList = panel.getByRole("list", { name: "History" });
		const history = within(historyList);
		history.getByText("Asked for");
		history.getByText("Latest attempt started");
		await expect(history.getAllByRole("listitem")).toHaveLength(2);
		await expect(historyList).not.toHaveTextContent(/after it was asked for|running/u);
	},
};

export const ScanWithManyRuns: Story = {
	args: { review: ready(reviewDetailScanManyJobs), onRequestReview: undefined },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("Scan");
		await expect(panel.queryByText("Feedback", { exact: true })).not.toBeInTheDocument();
		await expect(panel.queryByText(/observations and feedback/u)).not.toBeInTheDocument();
		await expect(panel.getByRole("link", { name: "Open the scan report" })).toHaveAttribute(
			"href",
			expect.stringContaining(`/admin/practices/scans/${reviewDetailScanManyJobs.id}`),
		);
		const runs = within(panel.getByRole("list", { name: "Timing" })).getAllByRole("listitem");
		await expect(runs).toHaveLength(reviewDetailScanManyJobs.runtime?.jobs.length ?? 0);
	},
};

export const PartialScan: Story = {
	args: {
		review: ready({
			...reviewDetailScanManyJobs,
			status: "PARTIAL",
			practices: reviewDetailScanManyJobs.practices.slice(0, 1).map((practice) => ({
				...practice,
				itemCounts: { listed: 4, reviewed: 4, reused: 1, withheld: 0, notReviewed: 0 },
			})),
		}),
		onRequestReview: undefined,
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Its observations cover only the work it reached.")).toBeVisible();
		await expect(panel.getByText("Files: 3 reviewed, 1 reused, 4 listed")).toBeVisible();
		await expect(panel.queryByText("Feedback", { exact: true })).not.toBeInTheDocument();
	},
};

/** Without its execution details the review keeps its request and its end, and claims no start. */
export const RuntimeExpired: Story = {
	args: { review: ready(reviewDetailExpiredRuntime) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText(RUNTIME_EXPIRED);
		panel.getByRole("list", { name: "Every practice in this review" });
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent(
			/Failed.*5 min from request.*finished/u,
		);
		const history = within(panel.getByRole("list", { name: "History" }));
		history.getByText("5 min after it was asked for");
		await expect(history.queryByText(/started/iu)).toBeNull();
	},
};

export const TimedOut: Story = {
	args: { review: ready(reviewDetailTimedOut) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		within(panel.getByRole("list", { name: "History" })).getByText(
			"It ran past the time it is allowed and was stopped.",
		);
		panel.getByText(/exceeded the 1200s timeout/u);
		// It recorded no result, so the sheet does not say any remain.
		await expect(panel.queryByText(/before it stopped/u)).toBeNull();
	},
};

/** A review that stops after a practice finished keeps that practice's results. */
export const TimedOutAfterSomeResults: Story = {
	args: {
		review: ready({
			...reviewDetailTimedOut,
			results: { met: 1, notMet: 1, doesNotApply: 0, couldNotDetermine: 0 },
			practices: [
				{
					...firstPractice,
					status: "DONE",
					reason: null,
					results: { met: 1, notMet: 1, doesNotApply: 0, couldNotDetermine: 0 },
				},
			],
		}),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		within(panel.getByRole("list", { name: "History" })).getByText(
			"It ran past the time it is allowed and was stopped.",
		);
		panel.getByText("Observations recorded before it stopped remain in this review.");
		await expect(factValue(panelElement, "Observations")).toHaveTextContent(
			"1 met, 1 not met · View problems",
		);
	},
};

/**
 * A review with no results or feedback to show says "None available", not "None": "None" would read
 * as a clean result, and a review that failed in preparation checked nothing. Its version is an
 * identifier, so it sits under Technical.
 */
export const FailedInPreparation: Story = {
	args: { review: ready(reviewDetailPreparationFailed) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(factValue(panelElement, "Observations")).toHaveTextContent(/^None available$/u);
		await expect(factValue(panelElement, "Feedback")).toHaveTextContent(/^None available$/u);
		await expect(panel.queryByText(/before it stopped/u)).toBeNull();
		// One label in the whole sheet, and it is in Technical, not with the primary facts.
		const version = panel.getByText("Reviewed version");
		await expect(version.closest("[data-sheet-section]")).toHaveAttribute(
			"data-sheet-section",
			"technical",
		);
		await expect(version.nextElementSibling).toHaveTextContent(
			String(reviewDetailPreparationFailed.itemVersion),
		);
		panel.getByRole("button", { name: "Copy reviewed version" });
	},
};

export const ProcessingFailed: Story = {
	args: { review: ready(reviewDetailProcessingFailed) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		within(panel.getByRole("list", { name: "Timing" })).getByText("Processing failed");
	},
};

export const CancelledOnBudget: Story = {
	args: { review: ready(reviewDetailCancelledOnBudget) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		within(panel.getByRole("list", { name: "History" })).getByText(
			"The scan’s budget was used up.",
		);
	},
};

export const CostPerAttempt: Story = {
	args: { review: ready({ ...reviewDetailCompleted, usage: reviewUsageTwoAttempts }) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText(/over 2 attempts/u);
		panel.getByRole("heading", { name: "Attempts" });
	},
};

export const CacheAcrossAttempts: Story = {
	args: { review: ready({ ...reviewDetailCompleted, usage: reviewUsageTwoAttempts }) },
	play: async () => {
		await expect(factValue(await settledDrawerPanel(), "Input from cache")).toHaveTextContent(
			"7.7% of 26,000 tokens",
		);
	},
};

export const CacheNotReported: Story = {
	args: {
		review: ready({
			...reviewDetailCompleted,
			usage: {
				...reviewUsageTwoAttempts,
				attempts: reviewUsageTwoAttempts.attempts.map((attempt) => ({
					...attempt,
					cacheReadTokens: 0,
					cacheWriteTokens: 0,
				})),
			},
		}),
	},
	play: async () => {
		const fact = factValue(await settledDrawerPanel(), "Input from cache");
		await expect(fact).toHaveTextContent("None reported");
		await expect(fact).not.toHaveTextContent("0%");
	},
};

export const TinyCacheRead: Story = {
	args: {
		review: ready({
			...reviewDetailCompleted,
			usage: {
				...reviewUsageTwoAttempts,
				attempts: reviewUsageTwoAttempts.attempts.map((attempt) => ({
					...attempt,
					cacheReadTokens: attempt.attempt === 0 ? 1 : 0,
					cacheWriteTokens: 0,
				})),
			},
		}),
	},
	play: async () => {
		const fact = factValue(await settledDrawerPanel(), "Input from cache");
		await expect(fact).toHaveTextContent("Under 0.1% of 24,001 tokens");
		await expect(fact).not.toHaveTextContent("0%");
	},
};

export const NoInputUsage: Story = {
	args: {
		review: ready({
			...reviewDetailCompleted,
			usage: {
				attempts: [],
				calls: 0,
				inputTokens: 0,
				outputTokens: 0,
				costUsd: "0",
				priced: true,
			},
		}),
	},
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(factValue(panel, "Input from cache")).toHaveTextContent("No usage recorded");
		await expect(factValue(panel, "Cost")).toHaveTextContent("No usage recorded");
	},
};

// Unknown price must not be shown as zero cost.
export const CostNotKnown: Story = {
	args: { review: ready({ ...reviewDetailCompleted, usage: reviewUsageUnpriced }) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("Not known: 1 attempt has no price");
	},
};

export const WithFeedbackChecks: Story = {
	args: {
		feedbackChecks: { status: "ready", checks: reviewFeedbackChecksOf(reviewDetailCompleted) },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("heading", { name: "Feedback checks of the review" });
	},
};

export const FeedbackChecksLoading: Story = {
	args: { feedbackChecks: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("heading", { name: "Feedback checks of the review" });
	},
};

export const FeedbackChecksFailed: Story = {
	args: { feedbackChecks: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("We could not load the feedback checks");
	},
};

/** A saved fold must not conceal a later failed read or its retry. */
export const FoldedFeedbackChecksFail: Story = {
	args: {
		feedbackChecks: { status: "ready", checks: reviewFeedbackChecksOf(reviewDetailCompleted) },
	},
	play: async ({ args, mount, userEvent }) => {
		await mount();
		let panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Feedback checks of the review" }));
		await expect(
			panel.getByRole("button", { name: "Feedback checks of the review" }),
		).toHaveAttribute("aria-expanded", "false");
		const retry = fn();
		await mount(
			<InLevelStack entry={reviewLevel(reviewDetailCompleted.id)} path={args.path}>
				{(level) => (
					<ReviewLevel
						{...args}
						{...level}
						feedbackChecks={{ status: "error", error: { status: 500 }, onRetry: retry }}
					/>
				)}
			</InLevelStack>,
		);
		panel = within(await settledDrawerPanel());
		await expect(panel.getByText("We could not load the feedback checks")).toBeVisible();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeEnabled();
		await mount(
			<InLevelStack entry={reviewLevel(reviewDetailCompleted.id)} path={args.path}>
				{(level) => <ReviewLevel {...args} {...level} />}
			</InLevelStack>,
		);
		panel = within(await settledDrawerPanel());
		const restored = panel.getByRole("button", { name: "Feedback checks of the review" });
		await expect(restored).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(restored);
		await expect(restored).toHaveAttribute("aria-expanded", "true");
	},
};

export const WithPracticeScripts: Story = {
	args: { review: ready(withScripts) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("heading", { name: "Practice scripts" });
		await expect(panel.getByRole("link", { name: "AI usage" })).toHaveAttribute(
			"href",
			"/w/demo/admin/usage",
		);
	},
};

/** A practice whose script already returned is no longer "next": the script's own line says how it ran. */
export const ReadyAfterItsScriptRan: Story = {
	args: {
		review: ready({
			...withScripts,
			practices: withScripts.practices.map((practice) =>
				practice.practiceSlug === "thin-controllers"
					? { ...practice, status: "READY", reason: null }
					: practice,
			),
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const list = panel.getByRole("list", { name: "Every practice in this review" });
		const row = list.querySelector('[data-practice-status="READY"]');
		await expect(row).not.toBeNull();
		await expect(row).toHaveTextContent("Ready");
		await expect(row).not.toHaveTextContent(/It runs next/u);
		await expect(row).toHaveTextContent(/returned a candidate observation/u);
	},
};

/** A practice that capture proved does not apply was never given to the runner, so it has a result and no script run. */
const notApplicable = withScripts.practices.find((practice) => practice.script === null);

export const DoesNotApplyWithoutAScriptRun: Story = {
	args: {
		review: ready({
			...withScripts,
			practices: withScripts.practices.map((practice) =>
				practice === notApplicable
					? {
							...practice,
							status: "DONE",
							reason: null,
							results: resultCounts(["DOES_NOT_APPLY"]),
						}
					: practice,
			),
		}),
	},
	play: async () => {
		await expect(notApplicable).toBeDefined();
		const name = notApplicable?.practiceName ?? "";
		const panel = within(await settledDrawerPanel());
		const list = panel.getByRole("list", { name: "Every practice in this review" });
		const row = within(list).getByText(name).closest("li");
		await expect(row).toHaveTextContent(/does not apply/iu);
		await expect(row).not.toHaveTextContent(/script/iu);
		const table = panel.getByRole("table", { name: "How each practice script ran in this review" });
		await expect(within(table).queryByRole("rowheader", { name })).toBeNull();
	},
};

export const WithoutPracticeScripts: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("heading", { name: "Practice scripts" })).toBeNull();
	},
};

export const AnotherReviewActive: Story = {
	args: { requestBlocked: REQUEST_BLOCKED.active },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("button", { name: "Request another review" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

export const WithRefusal: Story = {
	args: { requestOutcome: <p role="alert">A review of this work started a minute ago.</p> },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("alert");
	},
};

/**
 * The ask was accepted: the sheet keeps this review's own status and offers the way to the new one,
 * which says for itself whether it is queued or running.
 */
export const WithRequestedReview: Story = {
	args: {
		review: ready({ ...reviewDetailCompleted, status: "PARTIAL", stopReason: "RESULT_WITHHELD" }),
		requestBlocked: REQUEST_BLOCKED.active,
		requestOutcome: (
			<ReviewRequestedNotice
				review={<DetailStackLink entry={reviewLevel(REQUESTED_REVIEW_ID)} single />}
			/>
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const note = within(panel.getByRole("note"));
		// In this sheet's place, so Back returns to it.
		await expect(
			levelsOpenedBy(note.getByRole("link", { name: "View requested review" })),
		).toStrictEqual([`review:${REQUESTED_REVIEW_ID}`]);
		await expect(panel.getAllByText(REVIEW_STATUS_DEFS.PARTIAL.label).length).toBeGreaterThan(0);
		await expect(panel.queryByText(REVIEW_STATUS_DEFS.RUNNING.label)).toBeNull();
	},
};

export const Loading: Story = {
	args: { review: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("heading", { name: SHEET_SECTIONS.what })).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: { review: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("We could not load this review");
		await expect(panel.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { disableSnapshot: true } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const NoPracticeResultsRecordedYet: Story = {
	args: {
		...Running.args,
		review: ready({ ...reviewDetailRunning, practices: [] }),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("Practices appear here as the review reaches them.");
		await expect(panel.queryByRole("list", { name: "Every practice in this review" })).toBeNull();
	},
};

export const AmbiguousHistoricalResults: Story = {
	args: {
		review: ready({
			...reviewDetailCompleted,
			status: "PARTIAL",
			stopReason: "RESULT_WITHHELD",
			results: { met: 1, notMet: 1, doesNotApply: 0, couldNotDetermine: 0 },
			practices: reviewDetailCompleted.practices.slice(0, 1).map((practice) => ({
				...practice,
				status: "WITHHELD",
				reason: "LEGACY_AMBIGUOUS_RESULTS",
				results: { met: 1, notMet: 1, doesNotApply: 0, couldNotDetermine: 0 },
				itemCounts: { listed: 1, reviewed: 0, reused: 0, withheld: 1, notReviewed: 0 },
			})),
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const list = within(panel.getByRole("list", { name: "Every practice in this review" }));
		await expect(list.getByText("Withheld")).toBeVisible();
		await expect(
			list.getByText(
				"This review keeps several historical observations for the same practice and work without choosing one as its answer.",
			),
		).toBeVisible();
		await expect(list.queryByText("LEGACY_AMBIGUOUS_RESULTS")).toBeNull();
		// Partial is not an early stop: the History says why, and the sentence states only scope.
		within(panel.getByRole("list", { name: "History" })).getByText(
			"Its candidate observations were withheld.",
		);
		await expect(
			panel.getByText("Its observations and feedback cover only the practices it reviewed."),
		).toBeVisible();
		await expect(panel.queryByText(/ended early/u)).toBeNull();
	},
};
