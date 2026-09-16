import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { STORY_NOW } from "@/stories/story-clock";
import { expectNoPageOverflow, expectTablesScrollInPlace } from "@/test/reflow";

import { AdminLlmUsagePage } from "./AdminLlmUsagePage";
import { STORY_MONTH, usageReport, withOwnProvider } from "./story-mock-data";

// The report's own states are `AdminLlmUsageReport.stories.tsx`; this file pins what the page adds
// around it: the month navigation, and the states before a report exists.

const capped = withOwnProvider(usageReport());

const meta = {
	component: AdminLlmUsagePage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		month: STORY_MONTH,
		isCurrentMonth: true,
		canGoNext: false,
		workspaceSlug: "acme",
		report: capped,
		isLoading: false,
		error: null,
		now: new Date(STORY_NOW),
		onRetry: fn(),
		onEditOwnProviderCap: fn(),
	},
} satisfies Meta<typeof AdminLlmUsagePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Ready: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "AI usage" })).toBeVisible();
		canvas.getByRole("region", { name: /^Shared-model spend/u });
		canvas.getByRole("region", { name: /^Your provider spend/u });
	},
};

export const Loading: Story = {
	args: {
		report: undefined,
		isLoading: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("region", { name: /spend/u })).toBeNull();
		await expect(canvas.queryByRole("alert")).toBeNull();
	},
};

export const RetryableServerError: Story = {
	args: {
		report: undefined,
		error: { status: 500, detail: "Couldn't build the usage report." },
	},
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const ForbiddenError: Story = {
	args: {
		report: undefined,
		error: { status: 403, detail: "Workspace admin access is required." },
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Retry" })).toBeNull();
	},
};

export const MobileReflow: Story = {
	args: {
		report: {
			...capped,
			instanceTotalCostUsd: 25.0142,
			ownProviderTotalCostUsd: 10.12,
			instanceBudgetVerdict: "EXHAUSTED",
			instancePaused: true,
			ownProviderBudgetVerdict: "EXHAUSTED",
			ownProviderPaused: true,
		},
	},
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768, 1024] },
	},
	play: async ({ canvasElement }) => {
		await expectNoPageOverflow();
		await expectTablesScrollInPlace(canvasElement);
	},
};
