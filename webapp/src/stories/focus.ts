import { expect } from "storybook/test";

import { cn } from "cn";
import { buttonVariants } from "@/components/ui/button";

const px = (value: string) => Number.parseFloat(value);

/**
 * The layers of a computed `box-shadow` that paint. Tailwind composes a ring from five shadow
 * slots, and a slot not in use serialises as a zero-extent layer, so the layers that paint are the
 * ones with any length above 0px. Layers and the channels inside an `rgb(…)` are both
 * comma-separated.
 */
export const paintedLayers = (shadow: string) =>
	shadow
		.split(/,(?![^(]*\))/u)
		.filter((layer) => (layer.match(/-?\d*\.?\d+px/gu) ?? []).some((length) => px(length) !== 0));

/**
 * The edge colour of a keyboard-focused outline `Button`, the focus line every other control
 * matches. The specimen does not transition, so it reads the settled colour.
 */
async function focusedButtonEdge(): Promise<string> {
	const button = document.createElement("button");
	button.type = "button";
	button.className = cn(buttonVariants({ variant: "outline" }), "transition-none");
	document.body.append(button);
	button.focus();
	await expect(button.matches(":focus-visible")).toBe(true);
	const edge = getComputedStyle(button).borderTopColor;
	button.remove();
	return edge;
}

/**
 * Asserts that a keyboard-focused element draws a solid outline in the colour a focused `Button`
 * gives its edge. Give the element focus by keyboard first; it has focus again afterwards.
 */
export async function expectSolidFocusLine(element: HTMLElement) {
	await expect(element).toHaveFocus();
	const { outlineStyle, outlineWidth, outlineColor } = getComputedStyle(element);
	await expect(outlineStyle).toBe("solid");
	await expect(px(outlineWidth)).toBeGreaterThan(0);
	await expect(outlineColor).toBe(await focusedButtonEdge());
	element.focus();
	await expect(element).toHaveFocus();
}
