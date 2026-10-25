import { expect } from "storybook/test";

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
 * Asserts that a focused element draws a solid, coloured outline: the line that makes a focus ring
 * visible where its half-transparent halo alone is too faint against the ground.
 */
export async function expectSolidFocusLine(element: Element) {
	const { outlineStyle, outlineWidth, outlineColor } = getComputedStyle(element);
	await expect(outlineStyle).toBe("solid");
	await expect(px(outlineWidth)).toBeGreaterThan(0);
	await expect(outlineColor).not.toBe("rgba(0, 0, 0, 0)");
}
