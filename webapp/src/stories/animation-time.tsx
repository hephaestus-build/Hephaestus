import type { Decorator } from "@storybook/react";
import { useLayoutEffect } from "react";

/**
 * While `<html>` has this attribute, animations and transitions take their own time in the story tests.
 * Otherwise every one ends the moment it starts (`.storybook/vitest.setup.ts`).
 */
export const ANIMATION_TIME_ATTRIBUTE = "data-story-animation-time";

/** For a story that tests a transition itself, such as what happens while a drawer closes. */
export function withAnimationTime(): Decorator {
	return function AnimationTimeDecorator(Story) {
		useLayoutEffect(() => {
			document.documentElement.setAttribute(ANIMATION_TIME_ATTRIBUTE, "");
			return () => {
				document.documentElement.removeAttribute(ANIMATION_TIME_ATTRIBUTE);
			};
		}, []);
		return <Story />;
	};
}
