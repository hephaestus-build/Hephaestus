import { expect, waitFor } from "storybook/test";

/**
 * The six primitives in this kit that hang their popup off a Base UI `Positioner`, by the
 * `data-slot` each one stamps. Spelled out rather than matched loosely, so an open dialog — which
 * is portalled but not positioned — is not mistaken for one.
 */
export const POSITIONED_POPUPS = [
	"popover-content",
	"hover-card-content",
	"tooltip-content",
	"dropdown-menu-content",
	"select-content",
	"combobox-content",
]
	.map((slot) => `[data-slot='${slot}']`)
	.join(", ");

/**
 * Every enter animation currently running on `element` or on anything it sits inside.
 *
 * The ancestors are the point: a popup fades *itself* in, so the animation belongs to the popup
 * while the thing being asserted on is usually a `<dt>` or a `<p>` several levels down, which has no
 * animation of its own and would otherwise look settled the moment it mounts.
 */
function enteringAnimationsOf(element: Element): Animation[] {
	return document.getAnimations().filter(({ effect }) => {
		if (!(effect instanceof KeyframeEffect)) return false;
		return effect.target instanceof Element && effect.target.contains(element);
	});
}

/**
 * `toBeVisible()` on something inside a just-opened overlay, once the overlay has actually arrived.
 *
 * Base UI stamps `data-starting-style` on a popup for the frame it mounts in and clears it on the
 * next one; the enter styles it selects hold the popup at `opacity: 0` and `scale: .95`. Assert
 * inside that frame and `toBeVisible()` reads the transparent ancestor and fails — which is why the
 * weaker `toBeInTheDocument()` was reached for instead. It is not animation *duration*: reduced
 * motion is on in this suite and forcing `1ms !important` durations does not help, because the
 * attribute is a lifecycle marker rather than a timing one. Waiting for the attribute to clear is,
 * so this waits for that and then for whatever transition it handed off to.
 *
 * For the popup element itself rather than its contents, see `settledPopup`.
 */
export async function expectSettledVisible(element: HTMLElement): Promise<void> {
	await waitFor(() => {
		void expect(
			element.closest("[data-starting-style]"),
			"Still inside the overlay's starting-style frame, where everything in it is transparent.",
		).toBeNull();
	});
	await waitFor(() => {
		// Re-read the live animations rather than wait on a stale snapshot: an idle animation
		// has no finish to await, and a replacement must settle before its box is measured.
		const entering = enteringAnimationsOf(element).filter(
			(animation) => animation.playState !== "finished" && animation.playState !== "idle",
		);
		void expect(
			entering.map((animation) => ({ state: animation.playState, time: animation.currentTime })),
			"The overlay still has an unfinished enter animation.",
		).toEqual([]);
		void expect(element).toBeVisible();
	});
}

/**
 * The one open positioned popup, once it has landed — for the measuring assertions, which need the
 * box rather than a query hit, and would read a mid-flight `scale(.95)` as a popup that fits.
 */
export async function settledPopup(): Promise<HTMLElement> {
	const popup = await waitFor(() => {
		const open = document.querySelector<HTMLElement>(POSITIONED_POPUPS);
		if (open == null) {
			throw new Error("No overlay is open, so measuring the page would prove nothing.");
		}
		return open;
	});
	await expectSettledVisible(popup);
	return popup;
}

/**
 * The drawer level nearest the page, once it has landed. A level is portalled but not positioned, so
 * `settledPopup` above cannot see it, and it arrives over a transition rather than being simply
 * present — a play that measures the panel has to wait for it rather than take the first query hit.
 */
export async function settledDrawerPanel(): Promise<HTMLElement> {
	const panel = await waitFor(() => {
		const open = document.querySelector<HTMLElement>('[data-slot="drawer-popup"]');
		if (open == null) {
			throw new Error("No drawer level is open, so there is no panel to measure.");
		}
		return open;
	});
	await expectSettledVisible(panel);
	return panel;
}

/**
 * The mirror of `expectSettledVisible`: Base UI keeps a popup mounted through its exit animation, so
 * `open={false}` is not "gone" and an assertion taken on the next frame passes on a dialog that
 * never leaves.
 */
export async function expectDismissed(role: "dialog" | "alertdialog" = "dialog"): Promise<void> {
	await waitFor(() => {
		void expect(
			document.querySelector(`[role='${role}']`),
			"The overlay is still mounted; a dismissal that only flips `open` is not a dismissal.",
		).toBeNull();
	});
}
