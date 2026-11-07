import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";
import { expectClosedSelectShows } from "@/test/controls";

import { PracticeReviewBatch } from "./PracticeReviewBatch";
import { reviewBatch as batch } from "./review/fixtures";

const meta = {
	component: PracticeReviewBatch,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [1440] },
	},
	tags: ["autodocs"],
	args: {
		batches: [],
		totalBatches: 0,
		isLoading: false,
		isError: false,
		onRetry: fn(),
		isEstimating: false,
		onEstimate: fn(),
		isUpdating: false,
		onChangeStatus: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof PracticeReviewBatch>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/nothing is reviewed until you confirm/iu)).toBeVisible();
		await expectClosedSelectShows(canvas, /Kind of work/u, "Pull or merge requests");
		await expectClosedSelectShows(canvas, /How far back/u, "The last 30 days");
		await expect(canvas.getByText("No past work reviewed yet")).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const AwaitingConfirmation: Story = {
	args: { batches: [batch()] },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("128 pull or merge requests")).toBeVisible();
		await expect(canvas.getByText("$15.36")).toBeVisible();
		await userEvent.click(
			canvas.getByRole("button", { name: /review 128 pull or merge requests/iu }),
		);
		await expect(args.onChangeStatus).toHaveBeenCalledWith(args.batches[0], "RUNNING");
	},
};

export const CostUnknown: Story = {
	args: { batches: [batch({ estimatedCostUsd: null })] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Unknown")).toBeVisible();
	},
};

export const NothingInRange: Story = {
	args: { batches: [batch({ estimatedItems: 0, estimatedCostUsd: "0" })] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /nothing to review/iu })).toBeDisabled();
		await expect(canvas.getByText(/discard this estimate and try a longer one/iu)).toBeVisible();
	},
};

export const Running: Story = {
	args: { batches: [batch({ status: "RUNNING", submittedCount: 40, skippedCount: 12 })] },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Pause" }));
		await expect(args.onChangeStatus).toHaveBeenCalledWith(args.batches[0], "PAUSED");
	},
};

export const SomeCouldNotBeRead: Story = {
	args: {
		batches: [batch({ status: "RUNNING", submittedCount: 40, skippedCount: 12, failedCount: 3 })],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/3 could not be read, so were not reviewed/iu)).toBeVisible();
		await expect(canvas.getByText(/12 already reviewed/iu)).toBeVisible();
	},
};

export const PausedOnBudget: Story = {
	args: {
		batches: [
			batch({
				status: "PAUSED",
				pauseReason: "BUDGET_EXHAUSTED",
				submittedCount: 40,
				skippedCount: 12,
			}),
		],
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText(/nothing has been skipped/iu)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Resume" }));
		await expect(args.onChangeStatus).toHaveBeenCalledWith(args.batches[0], "RUNNING");
		await expectNoPageOverflow();
	},
};

export const ScheduleBatchesStayWithTheirSchedule: Story = {
	args: { batches: [batch({ origin: "SCHEDULE", status: "RUNNING", scheduleId: "s-1" })] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Reviewing past work")).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Estimate this review" })).toBeVisible();
	},
};

export const WithHistory: Story = {
	args: {
		batches: [
			batch({
				id: "22222222-2222-2222-2222-222222222222",
				status: "COMPLETED",
				submittedCount: 118,
				skippedCount: 10,
				finishedAt: new Date("2026-08-06T12:00:00Z"),
			}),
			batch({
				id: "33333333-3333-3333-3333-333333333333",
				itemKind: "scm.issue",
				status: "CANCELLED",
				estimatedItems: 60,
				submittedCount: 12,
				skippedCount: 3,
				finishedAt: new Date("2026-08-05T12:00:00Z"),
			}),
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Finished")).toBeVisible();
		await expect(canvas.getByText("Stopped")).toBeVisible();
	},
};

/** The list is one page of a longer history, and it says so. */
export const OlderReviewsNotListed: Story = {
	args: {
		...WithHistory.args,
		totalBatches: 25,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/23 older reviews are\s+not listed/u)).toBeVisible();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Estimate this review" })).toBeDisabled();
	},
};

export const Error: Story = {
	args: { isError: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/could not load reviews of past work/iu)).toBeVisible();
		await expect(canvas.getByText(/running review is not affected/iu)).toBeVisible();
		// A failed read knows nothing of the history or of a running review.
		await expect(canvas.queryByText("No past work reviewed yet")).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Estimate this review" })).toBeDisabled();
	},
};

export const EndedScheduledRunStaysWithItsSchedule: Story = {
	args: { batches: [batch({ origin: "SCHEDULE", status: "BUDGET_EXHAUSTED", scheduleId: "s-1" })] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Reviewing past work")).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "Resume" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Estimate this review" })).toBeVisible();
	},
};
