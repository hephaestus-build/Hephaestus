import { expect } from "storybook/test";

/**
 * The 320px reflow check (WCAG 2.2 SC 1.4.10). The story runner's browser viewport is fixed, so a
 * story opts in with `parameters: { reflow: true }`, which `.storybook/preview.tsx` answers by
 * rendering it inside a 320px box; this asserts nothing in that box scrolls sideways.
 */
export async function expectNoHorizontalOverflow(canvasElement: HTMLElement): Promise<void> {
	const box = canvasElement.querySelector<HTMLElement>("[data-reflow]");
	await expect(box, "the story sets parameters.reflow").not.toBeNull();
	await expect(box?.clientWidth).toBe(320);
	await expect(box?.scrollWidth).toBeLessThanOrEqual(320);
}

/** Whether an element lies inside the reflow box horizontally, rather than clipped off its edge. */
export async function expectInsideReflowBox(
	canvasElement: HTMLElement,
	element: HTMLElement,
): Promise<void> {
	const box = canvasElement.querySelector<HTMLElement>("[data-reflow]")?.getBoundingClientRect();
	const rect = element.getBoundingClientRect();
	await expect(rect.width).toBeGreaterThan(0);
	await expect(rect.right).toBeLessThanOrEqual(box?.right ?? 0);
}
