import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";
import { expectClosedSelectShows } from "@/test/controls";

import { sweepSchedule as schedule } from "./fixtures";
import { PracticeReviewSweepSchedule } from "./PracticeReviewSweepSchedule";

const meta = {
	component: PracticeReviewSweepSchedule,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [1440] },
	},
	tags: ["autodocs"],
	args: {
		schedules: [],
		isLoading: false,
		isError: false,
		onRetry: fn(),
		isSaving: false,
		onCreate: fn(),
		onReplace: fn(),
		onDelete: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof PracticeReviewSweepSchedule>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NothingScheduled: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/nothing is checked on a schedule/iu);
		await expectClosedSelectShows(canvas, /Kind of work/u, "Pull or merge requests");
		await expectClosedSelectShows(canvas, /How often/u, "Every day");
		canvas.getByRole("button", { name: "Start checking pull or merge requests" });
		canvas.getByText(/not just the first/iu);
		await expectNoPageOverflow();
	},
};

/**
 * The window offered must never exceed what the server accepts, or the admin's first attempt is a
 * 400 explaining a rule the form should have expressed.
 */
export const WeeklyOffersAFullWeek: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: /How often/u }));
		await userEvent.click(await screen.findByRole("option", { name: "Every week" }));

		await expectClosedSelectShows(canvas, /How far back/u, "The last 7 days");
	},
};

export const Running: Story = {
	args: { schedules: [schedule()] },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/every day, covering the last 2 days/iu);
		canvas.getByRole("button", { name: "Pause checking pull or merge requests" });
		canvas.getByRole("button", { name: "Remove the recurring check on pull or merge requests" });
		await expectNoPageOverflow();
	},
};

export const NotRunYet: Story = {
	args: { schedules: [schedule({ lastRunAt: undefined })] },
	play: async ({ canvas }) => {
		canvas.getByText(/has not checked anything yet/iu);
	},
};

export const Paused: Story = {
	args: { schedules: [schedule({ enabled: false })] },
	play: async ({ canvas }) => {
		canvas.getByText(/nothing is being checked/iu);
		canvas.getByRole("button", { name: "Resume checking pull or merge requests" });
	},
};

/**
 * The combination that can contradict itself: a paused schedule must not promise a first check
 * "within the hour", so the whole line is written out rather than matched a clause at a time.
 */
export const PausedBeforeItEverRan: Story = {
	args: { schedules: [schedule({ enabled: false, lastRunAt: undefined })] },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Every day, covering the last 2 days. Paused, so nothing is being checked. It has not checked anything yet.",
			),
		).toBeVisible();
	},
};

export const EveryKindScheduled: Story = {
	args: {
		schedules: [
			schedule(),
			schedule({
				id: "33333333-3333-3333-3333-333333333333",
				artifactKind: "scm.issue",
				cadence: "WEEKLY",
				lookbackDays: 7,
			}),
		],
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("button", { name: /start checking/iu }),
		).not.toBeInTheDocument();
	},
};

export const CouldNotLoad: Story = {
	args: { isError: true },
	play: async ({ canvas }) => {
		canvas.getByText(/recurring checks couldn't be loaded/iu);
		// The failure is about this screen, not the workspace: read as "stopped", it costs a second
		// check scheduled over the same work.
		canvas.getByText(/still running/iu);
		canvas.getByRole("button", { name: "Try again" });
	},
};
