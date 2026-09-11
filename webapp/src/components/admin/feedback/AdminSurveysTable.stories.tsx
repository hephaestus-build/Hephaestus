import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { Survey } from "@/api/types.gen";
import { STORY_NOW } from "@/components/common/story-clock";
import {
	adminSurvey,
	endedSurvey,
	pausedSurvey,
	scheduledSurvey,
} from "@/components/feedback/product-survey-fixtures";
import { withStandardPage } from "@/stories/decorators";

import { AdminSurveysTable } from "./AdminSurveysTable";

const surveys: Survey[] = [adminSurvey, scheduledSurvey, pausedSurvey, endedSurvey];

const ready = (rows: Survey[], onPageChange = fn()) => ({
	status: "ready" as const,
	surveys: rows,
	page: 0,
	totalPages: 1,
	onPageChange,
});

const meta = {
	title: "Instance admin/Product feedback/Surveys table",
	component: AdminSurveysTable,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	args: {
		now: STORY_NOW,
		pendingIds: new Set<string>(),
		onToggleActive: fn(),
		onEnd: fn(),
		onDelete: fn(),
	},
	argTypes: { pendingIds: { control: false } },
	tags: ["autodocs"],
} satisfies Meta<typeof AdminSurveysTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The words inside the status badge, as distinct from the same word in the schedule column. */
const badge = (canvas: { getByText: typeof screen.getByText }, label: string) =>
	canvas.getByText(label, { selector: "[data-slot='badge'] > span" });

/** One row per availability, so the badge, the schedule wording and the menu can be compared. */
export const Default: Story = {
	args: { state: ready(surveys) },
	play: async ({ canvas, args }) => {
		// Availability is derived from the schedule, not stored.
		await expect(badge(canvas, "Open")).toBeVisible();
		await expect(badge(canvas, "Scheduled")).toBeVisible();
		await expect(badge(canvas, "Paused")).toBeVisible();
		await expect(badge(canvas, "Ended")).toBeVisible();
		// 17 of 42 rounds to 40%; a survey nobody was invited to shows no rate at all.
		await expect(canvas.getByText("· 40%")).toBeVisible();
		await expect(canvas.getByText("0 of 0")).toBeVisible();

		await userEvent.click(canvas.getByRole("button", { name: "Actions for Mentor conversations" }));
		await userEvent.click(await screen.findByRole("menuitem", { name: "Resume" }));
		await expect(args.onToggleActive).toHaveBeenCalledWith(pausedSurvey, true);

		// An ended survey can neither be paused nor ended again; it can only be deleted.
		await userEvent.click(
			canvas.getByRole("button", { name: "Actions for Release 0.70 retrospective" }),
		);
		await expect(await screen.findByRole("menuitem", { name: "Delete" })).toBeVisible();
		await expect(screen.queryByRole("menuitem", { name: "End now" })).not.toBeInTheDocument();
		await expect(screen.queryByRole("menuitem", { name: "Pause" })).not.toBeInTheDocument();
		await userEvent.keyboard("{Escape}");
	},
};

export const ConfirmsBeforeEnding: Story = {
	args: { state: ready([adminSurvey]) },
	play: async ({ canvas, args }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Actions for Help improve practice feedback" }),
		);
		await userEvent.click(await screen.findByRole("menuitem", { name: "End now" }));
		const dialog = await screen.findByRole("alertdialog", { name: "End the survey now?" });
		await expect(dialog).toHaveTextContent("Responses stay.");
		await expect(args.onEnd).not.toHaveBeenCalled();
		await userEvent.click(screen.getByRole("button", { name: "End survey" }));
		await expect(args.onEnd).toHaveBeenCalledWith(adminSurvey);
	},
};

export const ConfirmsBeforeDeleting: Story = {
	args: { state: ready([adminSurvey]) },
	play: async ({ canvas, args }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Actions for Help improve practice feedback" }),
		);
		await userEvent.click(await screen.findByRole("menuitem", { name: "Delete" }));
		// 17 responded and 4 declined: both are rows the deletion takes with it.
		await screen.findByRole("alertdialog", { name: "Delete this survey and its 21 responses?" });
		await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
		await expect(args.onDelete).not.toHaveBeenCalled();
	},
};

export const Pending: Story = {
	args: { state: ready([adminSurvey]), pendingIds: new Set([adminSurvey.id]) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Actions for Help improve practice feedback" }),
		).toBeDisabled();
	},
};

export const Empty: Story = {
	args: { state: ready([]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No surveys yet")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const Error: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
};

export const NarrowViewport: Story = {
	args: { state: ready(surveys) },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
