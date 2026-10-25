import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { practiceCounts } from "./fixtures";
import { PracticeResultsSummary } from "./PracticeResultsSummary";

function countsOf(practiceSlug: string) {
	const counts = practiceCounts.find((each) => each.practiceSlug === practiceSlug);
	if (counts === undefined) {
		throw new Error(`The fixtures hold no counts for ${practiceSlug}`);
	}
	return counts;
}

const thinControllers = countsOf("thin-controllers");
const productLanguage = countsOf("product-language");

/**
 * The Observations tab narrowed to one practice: the practice's counts over the chosen days, above its
 * rows. A practice with no result in those days says so instead of showing zeros.
 */
const meta = {
	component: PracticeResultsSummary,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		practiceSlug: thinControllers.practiceSlug,
		practiceName: thinControllers.practiceName,
		rangeLabel: "Last 30 days",
		counts: { status: "ready", counts: thinControllers, stale: false },
	},
} satisfies Meta<typeof PracticeResultsSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

function figureOf(canvas: StoryContext["canvas"], label: string) {
	return canvas.getByText(label, { selector: "dt" }).nextElementSibling;
}

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 2 })).toHaveTextContent(
			thinControllers.practiceName,
		);
		await expect(figureOf(canvas, "Observations")).toHaveTextContent("33");
		await expect(figureOf(canvas, "Marked incorrect")).toHaveTextContent("1 of 33");
		await expect(figureOf(canvas, "Feedback delivered")).toHaveTextContent("7 of 12");
		await expect(canvas.getByRole("link", { name: "Edit in Practice setup" })).toBeVisible();
	},
};

/** A practice whose results composed no feedback says "None", not "0 of 0". */
export const NoFeedback: Story = {
	args: {
		practiceSlug: productLanguage.practiceSlug,
		practiceName: productLanguage.practiceName,
		counts: { status: "ready", counts: productLanguage, stale: false },
	},
	play: async ({ canvas }) => {
		await expect(figureOf(canvas, "Feedback delivered")).toHaveTextContent("None");
	},
};

export const NoResultsInRange: Story = {
	args: { counts: { status: "ready", counts: undefined, stale: false } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No review recorded an observation about this practice in these days."),
		).toBeVisible();
	},
};

/** The previous range's figures stand in while the new ones load: drained and marked busy. */
export const Stale: Story = {
	args: { counts: { status: "ready", counts: thinControllers, stale: true } },
	play: async ({ canvas }) => {
		await expect(figureOf(canvas, "Observations")?.closest("dl")).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

export const Loading: Story = {
	args: { counts: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 2 })).toHaveTextContent(
			thinControllers.practiceName,
		);
		await expect(canvas.queryByText("Observations", { selector: "dt" })).toBeNull();
	},
};

const retry = fn();
export const LoadFailed: Story = {
	args: { counts: { status: "error", error: { status: 503 }, onRetry: retry } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"We could not load the counts for this practice",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalledOnce();
	},
};
