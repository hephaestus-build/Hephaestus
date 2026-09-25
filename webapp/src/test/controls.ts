import type { StoryContext } from "@storybook/react-vite";
import { expect } from "storybook/test";

/**
 * A closed Base UI select shows the *label* for its value, looked up from the options.
 *
 * When that lookup misses, the trigger falls back to printing the raw value — so asserting the label
 * is what tells a review form that offers "Pull or merge requests" apart from one that asks an admin
 * to authorise "scm.pull_request", which is not a decision anyone outside the schema can check.
 * Worth stating because the fallback is silent: the control still renders, and still reads as filled.
 */
export async function expectClosedSelectShows(
	canvas: StoryContext["canvas"],
	name: RegExp | string,
	label: string,
) {
	await expect(canvas.getByRole("combobox", { name })).toHaveTextContent(label);
}

/**
 * SC 4.1.2: `pointer-events-none` blocks the mouse and nothing else, so the contract is the native
 * `disabled` attribute. The focus check is what an `aria-disabled` look-alike would fail.
 */
export async function expectGenuinelyDisabled(control: HTMLElement) {
	await expect(control).toBeDisabled();
	const before = document.activeElement;
	control.focus();
	await expect(document.activeElement).toBe(before);
}

/**
 * The same, for a Base UI `<span role="switch">`: no native `disabled`, so `aria-disabled` plus
 * removal from the tab order is the whole contract. Neither says the press is ignored.
 */
export async function expectUnavailable(control: HTMLElement) {
	await expect(control).toHaveAttribute("aria-disabled", "true");
	await expect(control).toHaveAttribute("tabindex", "-1");
}

const MINIMUM_TOUCH_TARGET_PX = 24;

/**
 * SC 2.5.8: a pointer target is at least {@link MINIMUM_TOUCH_TARGET_PX} CSS px a side. A control
 * smaller than its target draws the rest with a `before:absolute before:-inset-*` pseudo-element,
 * which no bounding box reports, so the target is the control's box widened by those insets.
 *
 * An absolutely positioned `::before` only widens *this* control's target when the control is the
 * containing block it resolves its insets against — a `static` control leaves the overlay anchored
 * to some ancestor, where it is hit area for something else. And only a negative inset widens
 * anything: a decorative overlay inset *inwards* is drawn over the control, not around it, so its
 * positive insets are clamped to zero rather than shrinking a target the control already meets.
 */
export async function expectTouchTarget(control: HTMLElement) {
	const box = control.getBoundingClientRect();
	const before = getComputedStyle(control, "::before");
	const drawn =
		before.content !== "none" &&
		before.position === "absolute" &&
		getComputedStyle(control).position !== "static";
	const outset = (inset: string) => (drawn ? Math.max(0, -Number.parseFloat(inset) || 0) : 0);
	await expect(box.width + outset(before.left) + outset(before.right)).toBeGreaterThanOrEqual(
		MINIMUM_TOUCH_TARGET_PX,
	);
	await expect(box.height + outset(before.top) + outset(before.bottom)).toBeGreaterThanOrEqual(
		MINIMUM_TOUCH_TARGET_PX,
	);
}
