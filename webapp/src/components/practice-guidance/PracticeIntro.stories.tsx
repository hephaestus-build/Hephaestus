import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { bundledGuidance, bundledPractice } from "@/stories/practice-guidance-story-mock-data";
import { Stateful } from "@/stories/stateful";

import { type PracticeGuidanceState, PracticeIntro } from "./PracticeIntro";

const ready = {
	status: "ready",
	visual: bundledGuidance.visual,
	guide: bundledGuidance.guide,
} satisfies PracticeGuidanceState;

/**
 * The top of a practice's level. The words come with the standing; the picture and the guide load
 * on their own, so only their area waits or fails. Whether the reader hid it is the route's, kept
 * per practice; here the story holds it.
 */
const meta = {
	component: PracticeIntro,
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
	args: {
		practice: bundledPractice,
		guidance: ready,
		hidden: false,
		onHiddenChange: fn(),
	},
	argTypes: {
		// A discriminated union renders as a free-text box, which cannot produce a valid value.
		guidance: { control: false },
	},
	render: (args) => (
		<Stateful initial={args.hidden}>
			{(hidden, setHidden) => (
				<PracticeIntro
					{...args}
					hidden={hidden}
					onHiddenChange={(next) => {
						args.onHiddenChange(next);
						setHidden(next);
					}}
				/>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeIntro>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Why the practice matters, then the picture captioned with what good looks like. The guide waits
 * behind "Read more" and opens in place.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(bundledPractice.whyItMatters)).toBeVisible();
		// The caption names the figure, and the alt text names the picture inside it.
		const figure = canvas.getByRole("figure", { name: bundledPractice.whatGoodLooksLike });
		await expect(figure).toBeVisible();
		await expect(canvas.getByRole("img", { name: bundledGuidance.visual.alt })).toBeVisible();
		await expect(canvas.queryByText("What good looks like")).toBeNull();

		await expect(canvas.queryByRole("heading", { name: "How to do it" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Read more" }));
		await expect(canvas.getByRole("heading", { name: "How to do it" })).toBeVisible();
		await expect(
			canvas.getByRole("img", { name: /^Three changes in order: first a refactor/u }),
		).toBeVisible();
	},
};

/**
 * The toggle comes before what it hides, so hiding leaves it in place with the focus on it; it
 * shows it all again.
 */
export const HideAndShow: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Hide introduction" }));
		await expect(canvas.queryByText(bundledPractice.whyItMatters)).toBeNull();
		await expect(canvas.queryByRole("figure")).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Read more" })).toBeNull();
		const show = canvas.getByRole("button", { name: "Show introduction" });
		await expect(show).toHaveFocus();

		await userEvent.click(show);
		await expect(canvas.getByText(bundledPractice.whyItMatters)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Hide introduction" })).toHaveFocus();
	},
};

/** A reader who hid the introduction on an earlier visit arrives with it hidden. */
export const Hidden: Story = {
	args: { hidden: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Introduction" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Show introduction" })).toBeVisible();
		await expect(canvas.queryByText(bundledPractice.whyItMatters)).toBeNull();
	},
};

/**
 * A practice with words and no picture or guide: what good looks like stands under its own label,
 * and there is nothing to read more of.
 */
export const WordsOnly: Story = {
	args: { guidance: { status: "ready" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "What good looks like" })).toBeVisible();
		await expect(canvas.getByText(bundledPractice.whatGoodLooksLike)).toBeVisible();
		await expect(canvas.queryByRole("figure")).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Read more" })).toBeNull();
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
		await expect(canvas.queryByRole("button", { name: "Read more" })).toBeNull();
	},
};

/** The picture and guide failed: one quiet sentence and a retry, and the words stay. */
export const LoadFailed: Story = {
	args: {
		guidance: { status: "error", error: new Error("Unavailable"), onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(bundledPractice.whyItMatters)).toBeVisible();
		await expect(canvas.getByRole("heading", { name: "What good looks like" })).toBeVisible();
		await expect(
			canvas.getByText(/We could not load the picture and guide for this practice\./u),
		).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Try again" })).toBeVisible();
	},
};

/** The catalog has nothing to say about the practice, so nothing is drawn, not even the toggle. */
export const Empty: Story = {
	args: { practice: {}, guidance: { status: "ready" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("region", { name: "Introduction" })).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

/** At 320px the picture scales to the column and the controls wrap; nothing leaves it. */
export const MobileReflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		const region = canvas.getByRole("region", { name: "Introduction" });
		await expect(region.scrollWidth).toBeLessThanOrEqual(region.clientWidth);
		await expect(canvas.getByRole("button", { name: "Hide introduction" })).toBeVisible();
	},
};
