import { useLayoutEffect, useState } from "react";

/**
 * False on the first render, true from the commit onwards. Base UI's `useTransitionStatus` seeds
 * `mounted` from `open`, so a drawer that mounts already open never runs the branch that sets
 * `starting`, gets no `data-starting-style` frame, and appears at rest. Anything mounted
 * from the URL does: a drawer level, a thread panel.
 *
 * A layout effect, not a frame: `requestAnimationFrame` can land after a caller has looked for the
 * panel and found nothing.
 */
export function useArrived(): boolean {
	const [arrived, setArrived] = useState(false);
	// oxlint-disable-next-line react/set-state-in-effect -- The extra render is the point: the closed frame has to be committed before the level opens, which is the only way Base UI runs its enter transition.
	useLayoutEffect(() => setArrived(true), []);
	return arrived;
}
