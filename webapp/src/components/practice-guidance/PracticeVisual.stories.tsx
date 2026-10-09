import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { bundledGuidance, unthemedVisual } from "@/stories/practice-guidance-story-mock-data";

import { PracticeVisual } from "./PracticeVisual";

const BLACK = "rgb(0, 0, 0)";
const NO_GROUND = "rgba(0, 0, 0, 0)";
/** `--background` in the light theme. */
const LIGHT_GROUND = "oklch(1 0 0)";

/** A shape's paint once the theme has colored it. */
function fillOf(canvas: HTMLElement, selector: string): string {
	const shape = canvas.querySelector(selector);
	if (!shape) {
		throw new Error(`The picture has no ${selector}`);
	}
	return getComputedStyle(shape).fill;
}

/**
 * The picture a practice opens with, bare: no border, card or padding, so whoever places it owns the
 * one surface around it. Its `pv-*` classes take the theme's tokens, so the same markup reads in both
 * themes. A picture that brings its own colors sits on the light ground instead.
 */
const meta = {
	component: PracticeVisual,
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<div className="max-w-2xl">
				<Story />
			</div>
		),
	],
	args: { svg: bundledGuidance.visual.svg, alt: bundledGuidance.visual.alt },
	argTypes: {
		// Markup, not a sentence: a text box would only invite a picture the sanitizer then empties.
		svg: { control: false },
	},
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeVisual>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The bundled picture: one image named by its alt text, its shapes in theme colors, no frame. */
export const Default: Story = {
	play: async ({ canvas, args }) => {
		const drawn = canvas.getByRole("img", { name: args.alt });
		await expect(drawn).toBeVisible();
		// The accent and the ink are theme tokens, not SVG's default black, on the caller's ground.
		await expect(fillOf(drawn, ".pv-fill-accent")).not.toBe(BLACK);
		await expect(fillOf(drawn, "text.pv-fill-ink")).toBe(getComputedStyle(drawn).color);
		await expect(getComputedStyle(drawn).backgroundColor).toBe(NO_GROUND);
		// No frame of its own, so a caller's surface around it is the only one.
		await expect(getComputedStyle(drawn).borderTopWidth).toBe("0px");
	},
};

/** The same markup in the dark theme: the ink follows the theme's foreground. */
export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas, args }) => {
		const drawn = canvas.getByRole("img", { name: args.alt });
		await expect(fillOf(drawn, "text.pv-fill-ink")).toBe(getComputedStyle(drawn).color);
	},
};

/** A picture with its own colors and no `pv-*` class, on the light ground that keeps it legible. */
export const OwnColors: Story = {
	args: unthemedVisual,
	play: async ({ canvas, args }) => {
		const drawn = canvas.getByRole("img", { name: args.alt });
		await expect(getComputedStyle(drawn).backgroundColor).toBe(LIGHT_GROUND);
	},
};

/** The ground stays light in the dark theme: the picture's dark ink would vanish on the dark card. */
export const OwnColorsDark: Story = {
	...OwnColors,
	globals: { theme: "dark" },
};
