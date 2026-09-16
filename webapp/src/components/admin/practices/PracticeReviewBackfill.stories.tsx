import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectClosedSelectShows } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

import { PracticeReviewBackfill } from "./PracticeReviewBackfill";
import { backfillRun as run } from "./story-mock-data";

const meta = {
	title: "Workspace admin/Practices/Review/Past work",
	component: PracticeReviewBackfill,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [1440] },
	},
	tags: ["autodocs"],
	args: {
		runs: [],
		isLoading: false,
		isError: false,
		onRetry: fn(),
		isEstimating: false,
		onEstimate: fn(),
		isUpdating: false,
		onConfirm: fn(),
		onCancel: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof PracticeReviewBackfill>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ChooseARange: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/nothing is reviewed until you confirm/iu);
		await expectClosedSelectShows(canvas, /Kind of work/u, "Pull or merge requests");
		await expectClosedSelectShows(canvas, /How far back/u, "The last 30 days");
		await expectNoPageOverflow();
	},
};

export const AwaitingConfirmation: Story = {
	args: { runs: [run()] },
	play: async ({ canvas }) => {
		canvas.getByText("128 pull or merge requests");
		canvas.getByText("$15.36");
		canvas.getByRole("button", { name: /review 128 pull or merge requests/iu });
	},
};

/**
 * A workspace with no priced reviews yet has nothing to forecast from, and a $0.00 there would
 * invite exactly the unconsidered spend this screen exists to prevent.
 */
export const CostUnknown: Story = {
	args: { runs: [run({ estimatedCostUsd: undefined })] },
	play: async ({ canvas }) => {
		canvas.getByText("Unknown");
	},
};

export const NothingInRange: Story = {
	args: { runs: [run({ estimatedArtifacts: 0, estimatedCostUsd: 0 })] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /nothing to review/iu })).toBeDisabled();
		canvas.getByText(/discard this and try a longer one/iu);
	},
};

export const Running: Story = {
	args: {
		runs: [
			run({ status: "RUNNING", submittedCount: 40, passedCount: 12, confirmedByAccountId: 7 }),
		],
	},
};

/**
 * Work the campaign could not read is not work it measured and found nothing in; folding the two
 * together would leave a baseline in which the two absences are indistinguishable.
 */
export const SomeCouldNotBeRead: Story = {
	args: {
		runs: [
			run({
				status: "RUNNING",
				submittedCount: 40,
				passedCount: 12,
				failedCount: 3,
				confirmedByAccountId: 7,
			}),
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/3 could not be read, and stay unmeasured/iu)).toBeVisible();
		await expect(canvas.getByText(/12 already measured/iu)).toBeVisible();
	},
};

export const PausedOnBudget: Story = {
	args: {
		runs: [
			run({
				status: "PAUSED",
				pauseReason: "BUDGET_EXHAUSTED",
				submittedCount: 40,
				passedCount: 12,
				confirmedByAccountId: 7,
			}),
		],
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/nothing has been skipped/iu);
		await expectNoPageOverflow();
	},
};

export const WithHistory: Story = {
	args: {
		runs: [
			run({
				id: "22222222-2222-2222-2222-222222222222",
				status: "COMPLETED",
				submittedCount: 118,
				passedCount: 10,
				confirmedByAccountId: 7,
				finishedAt: new Date("2026-08-06T12:00:00Z"),
			}),
			run({
				id: "33333333-3333-3333-3333-333333333333",
				artifactKind: "scm.issue",
				status: "CANCELLED",
				estimatedArtifacts: 60,
				submittedCount: 12,
				passedCount: 3,
				confirmedByAccountId: 7,
				finishedAt: new Date("2026-08-05T12:00:00Z"),
			}),
		],
	},
};

export const Loading: Story = {
	args: { isLoading: true },
};

/**
 * The failure is about this list, not about the campaign. Read as "stopped", it costs a second
 * backfill confirmed over the same stretch of history — twice the spend.
 */
export const LoadFailed: Story = {
	args: { isError: true },
	play: async ({ canvas }) => {
		canvas.getByText(/backfills couldn't be loaded/iu);
		canvas.getByText(/already running is unaffected/iu);
	},
};
