import { useEffect, useEffectEvent, useRef, useState } from "react";
import { useMediaQuery } from "usehooks-ts";

/** How long the sheen takes to cross, in milliseconds. */
export const ARRIVAL_SHEEN_MS = 1300;

/** How long the still wash stays under reduced motion, in milliseconds. */
export const ARRIVAL_STILL_MS = 2000;

/**
 * A one-time sheen over what a link arrived at, such as the feedback card an observation points
 * to: a band of the new card's mentor wash sweeps once diagonally from the top left to the bottom
 * right, then the highlight leaves the page as it was. No pulse and no repeat. Under reduced motion
 * nothing moves: a still wash stays for {@link ARRIVAL_STILL_MS}, then goes. Place it as the first
 * child of a `relative` box; it takes the box's rounding and lets every press through.
 */
export function ArrivalHighlight({ onDone }: { onDone?: () => void }) {
	const still = useMediaQuery("(prefers-reduced-motion: reduce)", { initializeWithValue: true });
	const [done, setDone] = useState(false);
	const band = useRef<HTMLSpanElement>(null);
	// The latest callback, read when the highlight ends: it belongs to the mount, not to a render.
	const finished = useEffectEvent(() => onDone?.());
	useEffect(() => {
		const end = () => {
			setDone(true);
			finished();
		};
		if (still) {
			const timer = window.setTimeout(end, ARRIVAL_STILL_MS);
			return () => window.clearTimeout(timer);
		}
		// The Web Animations API plays the sweep once, with no keyframes in the stylesheet.
		// Where the Web Animations API is missing (a test's DOM), the sheen ends at once.
		const element = band.current;
		const animation =
			typeof element?.animate === "function"
				? element.animate(
						[
							{ transform: "translateX(-140%) skewX(-20deg)" },
							{ transform: "translateX(260%) skewX(-20deg)" },
						],
						{ duration: ARRIVAL_SHEEN_MS, easing: "ease-in-out", fill: "forwards" },
					)
				: undefined;
		if (animation === undefined) {
			end();
		} else {
			animation.onfinish = end;
		}
		return () => animation?.cancel();
	}, [still]);
	if (done) {
		return null;
	}
	return (
		<span
			aria-hidden
			data-arrival-highlight={still ? "still" : "sheen"}
			className="pointer-events-none absolute inset-0 z-10 overflow-hidden rounded-[inherit]"
		>
			{still ? (
				<span className="absolute inset-0 bg-mentor/10" />
			) : (
				<span
					ref={band}
					data-arrival-sheen=""
					className="absolute inset-y-0 left-0 w-1/2 bg-linear-to-r from-transparent via-mentor/15 to-transparent"
				/>
			)}
		</span>
	);
}
