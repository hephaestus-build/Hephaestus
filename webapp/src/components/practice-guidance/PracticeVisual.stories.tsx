import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { bundledGuidance, unthemedVisual } from "@/stories/practice-guidance-story-mock-data";

import { PracticeVisual } from "./PracticeVisual";

const BLACK = "rgb(0, 0, 0)";
const NO_GROUND = "rgba(0, 0, 0, 0)";
/** `--background` in the light theme. */
const LIGHT_GROUND = "oklch(1 0 0)";

/** The element the picture is drawn into: the one child of the image's wrapper. */
function canvasOf(image: HTMLElement): HTMLElement {
	const canvas = image.firstElementChild;
	if (!(canvas instanceof HTMLElement)) {
		throw new Error("The picture has no canvas");
	}
	return canvas;
}

/** A shape's paint once the theme has colored it. */
function fillOf(canvas: HTMLElement, selector: string): string {
	const shape = canvas.querySelector(selector);
	if (!shape) {
		throw new Error(`The picture has no ${selector}`);
	}
	return getComputedStyle(shape).fill;
}

/**
 * The picture a practice opens with. Its `pv-*` classes take the theme's tokens, so the same markup
 * reads in both themes. A picture that brings its own colors sits on the light ground instead.
 */
const meta = {
	component: PracticeVisual,
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
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

/** The bundled picture: one image named by its alt text, its shapes in theme colors. */
export const Default: Story = {
	play: async ({ canvas, args }) => {
		const image = canvas.getByRole("img", { name: args.alt });
		await expect(image).toBeVisible();
		const drawn = canvasOf(image);
		// The accent and the ink are theme tokens, not SVG's default black.
		await expect(fillOf(drawn, ".pv-fill-accent")).not.toBe(BLACK);
		await expect(fillOf(drawn, "text.pv-fill-ink")).toBe(getComputedStyle(drawn).color);
		await expect(getComputedStyle(drawn).backgroundColor).toBe(NO_GROUND);
	},
};

/** The same markup in the dark theme: the ink follows the theme's foreground. */
export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas, args }) => {
		const drawn = canvasOf(canvas.getByRole("img", { name: args.alt }));
		await expect(fillOf(drawn, "text.pv-fill-ink")).toBe(getComputedStyle(drawn).color);
	},
};

/** A picture with its own colors and no `pv-*` class, on the light ground that keeps it legible. */
export const OwnColors: Story = {
	args: unthemedVisual,
	play: async ({ canvas, args }) => {
		const drawn = canvasOf(canvas.getByRole("img", { name: args.alt }));
		await expect(getComputedStyle(drawn).backgroundColor).toBe(LIGHT_GROUND);
	},
};

/** The ground stays light in the dark theme: the picture's dark ink would vanish on the dark card. */
export const OwnColorsDark: Story = {
	...OwnColors,
	globals: { theme: "dark" },
};
