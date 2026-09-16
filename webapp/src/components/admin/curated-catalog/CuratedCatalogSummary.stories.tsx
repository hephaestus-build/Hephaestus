import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectNoOverflowingElement } from "@/test/reflow";

import { CuratedCatalogSummary } from "./CuratedCatalogSummary";

const meta = {
	component: CuratedCatalogSummary,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		onReviewChanges: fn(),
		removedDefaultsToReview: 0,
		summary: {
			total: 49,
			updatesChangingDetection: 0,
			updatesChangingWordingOnly: 0,
			updatesChangingPresentation: 0,
			editedHere: 0,
			yours: 0,
			notOffered: 0,
			noLongerShipped: 0,
		},
	},
} satisfies Meta<typeof CuratedCatalogSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

export const UpdatesWaiting: Story = {
	args: {
		onReviewChanges: fn(),
		summary: {
			total: 49,
			updatesChangingDetection: 2,
			updatesChangingWordingOnly: 5,
			updatesChangingPresentation: 1,
			editedHere: 3,
			yours: 0,
			notOffered: 0,
			noLongerShipped: 0,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("2 updates would change review rules")).toBeVisible();
		await expect(canvas.getByText("5 updates would change wording or guidance")).toBeVisible();
		await expect(canvas.getByText("1 update would change group appearance")).toBeVisible();
		await expect(canvas.getByText("8 Hephaestus changes need review")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Review changes" })).toBeVisible();
	},
};

export const RemovedDefault: Story = {
	args: {
		removedDefaultsToReview: 1,
		summary: {
			total: 49,
			updatesChangingDetection: 0,
			updatesChangingWordingOnly: 0,
			updatesChangingPresentation: 0,
			editedHere: 1,
			yours: 0,
			notOffered: 0,
			noLongerShipped: 1,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("1 Hephaestus change needs review")).toBeVisible();
		await expect(canvas.getByText("1 entry is no longer in Hephaestus defaults")).toBeVisible();
	},
};

export const ReviewingChanges: Story = {
	args: { ...UpdatesWaiting.args, reviewing: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Review changes" })).not.toBeInTheDocument();
	},
};

export const NarrowViewport: Story = {
	args: UpdatesWaiting.args,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
