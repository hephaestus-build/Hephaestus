import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { SyncJob } from "@/api/types.gen";
import { expectSettledVisible } from "@/test/overlay";

import { SyncJobsTable } from "./SyncJobsTable";

const runningJob: SyncJob = {
	id: 3,
	type: "RECONCILIATION",
	trigger: "MANUAL",
	status: "RUNNING",
	cancelRequested: false,
	createdAt: new Date("2026-07-14T10:00:00Z"),
	startedAt: new Date("2026-07-14T10:00:05Z"),
	itemsProcessed: 4,
	itemsTotal: 12,
};

const failedJob: SyncJob = {
	id: 1,
	type: "INITIAL",
	trigger: "LIFECYCLE",
	status: "FAILED",
	cancelRequested: false,
	createdAt: new Date("2026-07-01T09:00:00Z"),
	startedAt: new Date("2026-07-01T09:00:00Z"),
	finishedAt: new Date("2026-07-01T09:01:12Z"),
	errorSummary: "GitHub API rate limit exceeded after 3 retries",
};

const jobs: SyncJob[] = [
	runningJob,
	{
		id: 2,
		type: "RECONCILIATION",
		trigger: "SCHEDULED",
		status: "SUCCEEDED",
		cancelRequested: false,
		createdAt: new Date("2026-07-13T02:00:00Z"),
		startedAt: new Date("2026-07-13T02:00:00Z"),
		finishedAt: new Date("2026-07-13T02:04:30Z"),
		itemsProcessed: 40,
		itemsTotal: 40,
	},
	failedJob,
];

/** One row per job status, including the duration edges (a queued PENDING row has no duration). */
const allStatuses: SyncJob[] = [
	{
		id: 60,
		type: "RECONCILIATION",
		trigger: "SCHEDULED",
		status: "PENDING",
		cancelRequested: false,
		createdAt: new Date("2026-07-14T11:00:00Z"),
	},
	{
		id: 50,
		type: "RECONCILIATION",
		trigger: "MANUAL",
		status: "RUNNING",
		cancelRequested: false,
		createdAt: new Date("2026-07-14T10:59:00Z"),
		startedAt: new Date("2026-07-14T10:59:10Z"),
		itemsProcessed: 7,
		itemsTotal: 20,
	},
	{
		id: 40,
		type: "INITIAL",
		trigger: "LIFECYCLE",
		status: "SUCCEEDED",
		cancelRequested: false,
		createdAt: new Date("2026-07-14T08:00:00Z"),
		startedAt: new Date("2026-07-14T08:00:00Z"),
		finishedAt: new Date("2026-07-14T08:05:00Z"),
		itemsProcessed: 128,
		itemsTotal: 128,
	},
	{
		id: 30,
		type: "RECONCILIATION",
		trigger: "SCHEDULED",
		status: "SUCCEEDED_WITH_WARNINGS",
		cancelRequested: false,
		createdAt: new Date("2026-07-13T02:00:00Z"),
		startedAt: new Date("2026-07-13T02:00:00Z"),
		finishedAt: new Date("2026-07-13T02:03:20Z"),
		itemsProcessed: 39,
		itemsTotal: 40,
		errorSummary: "1 repository skipped: access revoked",
	},
	{
		id: 20,
		type: "BACKFILL",
		trigger: "MANUAL",
		status: "CANCELLED",
		cancelRequested: true,
		createdAt: new Date("2026-07-12T14:00:00Z"),
		startedAt: new Date("2026-07-12T14:00:00Z"),
		finishedAt: new Date("2026-07-12T14:00:45Z"),
		itemsProcessed: 12,
	},
	{
		id: 10,
		type: "INITIAL",
		trigger: "SYSTEM",
		status: "FAILED",
		cancelRequested: false,
		createdAt: new Date("2026-07-01T09:00:00Z"),
		startedAt: new Date("2026-07-01T09:00:00Z"),
		finishedAt: new Date("2026-07-01T09:01:12Z"),
		errorSummary:
			"GitHub API rate limit exceeded after 3 retries; the backoff window did not clear before the job deadline, so the pass was abandoned and will be retried on the next scheduled reconciliation.",
	},
];

/**
 * Paginated audit trail of sync jobs. Each row carries a status badge (its colour matched to the
 * outcome), the type and trigger read as one phrase ("Reconciliation · scheduled"), a start time that
 * ticks and carries its absolute instant in a tooltip, a computed duration ("Running…" while live, a
 * dash when a queued job has not started) and, on failures, an error hover.
 *
 * Correlating a failed run with a server log is this table's entire job, which is why "3 days ago" is
 * not good enough on its own and why the exact timestamp is always one hover away. Rows with a
 * persisted progress report expand — through the same `Collapsible`-as-`tbody` idiom the rest of the
 * surface uses, rather than a second hand-rolled expansion protocol.
 */
const meta = {
	component: SyncJobsTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof SyncJobsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { args: { jobs } };

/** Every status the wire can report, including the queued/no-duration and warning edges. */
export const AllStatuses: Story = { args: { jobs: allStatuses } };

/** Type and trigger are one phrase, not two columns — the second repeated one word down every row. */
export const TypeCarriesTrigger: Story = {
	args: { jobs },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("columnheader", { name: "Trigger" })).toBeNull();
		await expect(canvas.getAllByText("Reconciliation", { exact: false }).length).toBeGreaterThan(0);
		await expect(canvas.getAllByText(/· scheduled/iu).length).toBeGreaterThan(0);
	},
};

/** The start time is relative for scanning and absolute for grepping a log — hover gets the instant. */
export const StartedRevealsAbsoluteTime: Story = {
	args: { jobs },
	play: async ({ canvas }) => {
		const [firstRelativeStart] = canvas.getAllByText(/ago$/u);
		if (!firstRelativeStart) {
			throw new Error("No row rendered a relative start time");
		}
		await userEvent.hover(firstRelativeStart);
		await expectSettledVisible(await screen.findByText(/\d{4}, \d{2}:\d{2}:\d{2}$/u));
	},
};

/** The error hover surfaces the summary on demand, keyed to the failing row. No click, no focus trap. */
export const ErrorHover: Story = {
	args: { jobs: allStatuses },
	play: async ({ canvas }) => {
		await userEvent.hover(canvas.getByRole("button", { name: /error for job 10/iu }));
		await expectSettledVisible(await screen.findByText(/rate limit exceeded after 3 retries/iu));
	},
};

/** A job that persisted a progress report expands to it; one with nothing to show grows no chevron. */
export const ExpandProgressDetail: Story = {
	args: {
		jobs: [
			{
				...runningJob,
				progress: {
					phase: "pullRequests",
					currentStep: "Backfilling ls1intum/Artemis — issues #4812 → #3200",
					currentRepository: "ls1intum/Artemis",
					unitsCompleted: 1612,
					unitsTotal: 4812,
				},
			},
			failedJob,
		],
	},
	play: async ({ canvas }) => {
		// Only the job with a progress report is expandable.
		await expect(canvas.getAllByRole("button", { name: /show details for job/iu })).toHaveLength(1);
		await userEvent.click(canvas.getByRole("button", { name: /show details for job 3/iu }));
		await expectSettledVisible(await canvas.findByText(/backfilling ls1intum\/artemis/iu));
		canvas.getByText("Pull requests");
	},
};

export const Loading: Story = { args: { jobs: [], isLoading: true } };
export const ErrorState: Story = {
	args: { jobs: [], isError: true, error: new Error("Network error"), onRetry: fn() },
};
export const Empty: Story = { args: { jobs: [] } };
export const Paged: Story = {
	args: { jobs, page: 1, totalPages: 4, onPageChange: fn() },
};

export const FirstPage: Story = {
	args: { jobs, page: 0, totalPages: 4, onPageChange: fn() },
	play: async ({ canvas }) => {
		canvas.getByRole("navigation", { name: "pagination" });
		await expect(canvas.queryByText(/Page \d+ of \d+/u)).toBeNull();
	},
};
