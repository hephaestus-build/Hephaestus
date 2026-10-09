import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { bundledGuidance, bundledPractice } from "@/stories/practice-guidance-story-mock-data";
import { precedes } from "@/test/dom";

import { type PracticeGuidanceState, PracticeIntro } from "./PracticeIntro";

const ready = {
	status: "ready",
	visual: bundledGuidance.visual,
	guide: bundledGuidance.guide,
} satisfies PracticeGuidanceState;

/**
 * The top of a practice's level. The words come with the standing; the picture loads on its own, so
 * only its area waits or fails. There is no toggle: the introduction is short enough to stay, and
 * the longer guide is a tab of the level rather than a disclosure here.
 */
const meta = {
	component: PracticeIntro,
	parameters: { layout: "padded" },
	args: { practice: bundledPractice, guidance: ready },
	argTypes: {
		// A discriminated union renders as a free-text box, which cannot produce a valid value.
		guidance: { control: false },
	},
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeIntro>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Why the practice matters, then the picture with what good looks like directly under it as its
 * caption. The picture has no frame and spans the text column.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const lead = canvas.getByText(bundledPractice.whyItMatters);
		await expect(lead).toBeVisible();
		// The caption names the figure, and the alt text names the picture inside it.
		const figure = canvas.getByRole("figure", { name: bundledPractice.whatGoodLooksLike });
		const picture = canvas.getByRole("img", { name: bundledGuidance.visual.alt });
		await expect(picture).toBeVisible();
		await expect(precedes(lead, figure)).toBe(true);
		// The picture lines up with the words: same left edge, same column.
		await expect(picture.getBoundingClientRect().left).toBe(lead.getBoundingClientRect().left);
		await expect(picture.getBoundingClientRect().width).toBe(lead.getBoundingClientRect().width);
		await expect(canvas.queryByText("What good looks like")).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

/** A practice with words and no picture: what good looks like stands under its own label. */
export const WordsOnly: Story = {
	args: { guidance: { status: "ready" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "What good looks like" })).toBeVisible();
		await expect(canvas.getByText(bundledPractice.whatGoodLooksLike)).toBeVisible();
		await expect(canvas.queryByRole("figure")).toBeNull();
	},
};

/**
 * The words show at once. A slow picture gets its shape after a second, so a quick one never
 * flashes a placeholder.
 */
export const Loading: Story = {
	args: { guidance: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(bundledPractice.whyItMatters)).toBeVisible();
		await expect(canvas.queryByText("Loading the picture for this practice…")).toBeNull();
		// Until the placeholder shows, nothing claims the practice has no picture.
		await expect(canvas.queryByRole("heading", { name: "What good looks like" })).toBeNull();
		await canvas.findByText("Loading the picture for this practice…", undefined, { timeout: 3000 });
	},
};

/** The picture failed: one quiet sentence and a retry where it would be, and the words stay. */
export const LoadFailed: Story = {
	args: {
		guidance: { status: "error", error: new Error("Unavailable"), onRetry: fn() },
	},
	play: async ({ canvas }) => {
		const failure = canvas.getByText(
			/We could not load the picture and guide for this practice\./u,
		);
		await expect(failure).toBeVisible();
		await expect(precedes(canvas.getByText(bundledPractice.whyItMatters), failure)).toBe(true);
		await expect(
			precedes(failure, canvas.getByRole("heading", { name: "What good looks like" })),
		).toBe(true);
		await expect(canvas.getByRole("button", { name: "Try again" })).toBeVisible();
	},
};

/** The catalog has nothing to say about the practice and it has no picture, so nothing is drawn. */
export const Empty: Story = {
	args: { practice: {}, guidance: { status: "ready" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("region", { name: "Introduction" })).toBeNull();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

/** At 320px the picture scales down with the column; nothing leaves it. */
export const MobileReflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		const region = canvas.getByRole("region", { name: "Introduction" });
		await expect(region.scrollWidth).toBeLessThanOrEqual(region.clientWidth);
		await expect(canvas.getByRole("img", { name: bundledGuidance.visual.alt })).toBeVisible();
	},
};
