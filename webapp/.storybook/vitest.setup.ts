import { clearAllMocks } from "storybook/test";
import { afterEach, beforeEach, type MockInstance, vi } from "vitest";

import { ANIMATION_TIME_ATTRIBUTE } from "@/stories/animation-time";

/**
 * React reports duplicate or missing keys only on `console.error` and renders on. A row with a
 * shared key can be dropped or duplicated, so a story that logs one fails here.
 */
const KEY_ERRORS = [
	/Encountered two children with the same key/u,
	/Each child in a list should have a unique "key" prop/u,
];

/**
 * Every animation and transition here ends the moment it starts. A zero-duration animation applies its
 * end state when the style resolves, so an overlay never shows the opacity-0 first frame of its fade, in
 * which `toBeVisible()` reads it as hidden and fails by chance. The emulated reduced motion keeps fades,
 * so it does not prevent that frame. Base UI's mount and unmount waits on `getAnimations()` resolve at
 * once. Two kinds of story still need time:
 * - one that tests a transition itself opts out with `withAnimationTime`;
 * - one that tests an overlay's arrival through Base UI's `data-starting-style` frame, a lifecycle marker
 *   rather than a duration, waits with `expectSettledVisible`.
 */
if (document.head.querySelector("style[data-story-no-animation-time]") === null) {
	const style = document.createElement("style");
	style.dataset.storyNoAnimationTime = "";
	const scope = `:root:not([${ANIMATION_TIME_ATTRIBUTE}])`;
	style.textContent = `${scope} *, ${scope} *::before, ${scope} *::after {
	animation-duration: 0s !important;
	animation-delay: 0s !important;
	transition-duration: 0s !important;
	transition-delay: 0s !important;
}`;
	document.head.append(style);
}

let consoleErrors: MockInstance | undefined;

beforeEach(() => {
	clearAllMocks();
	consoleErrors = vi.spyOn(console, "error");
});

afterEach(() => {
	const keyErrors = (consoleErrors?.mock.calls ?? [])
		.map((args) => args.map(String).join(" "))
		.filter((text) => KEY_ERRORS.some((pattern) => pattern.test(text)));
	consoleErrors?.mockRestore();
	if (keyErrors.length > 0) {
		throw new Error(`React reported a key error:\n${keyErrors.join("\n")}`);
	}
});
