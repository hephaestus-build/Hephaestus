import { useEffect, useRef } from "react";

/**
 * Runs `onInView` whenever the element the returned ref is put on enters the viewport, which is
 * what a list uses to load its next page as a reader reaches the end of the one they have rather
 * than asking them to press for it.
 *
 * `enabled` is the whole guard: with it false no observer exists, so a caller turns it off while
 * the load it started is in flight and the sentinel cannot ask twice for the same page.
 */
export function useInView(onInView: () => void, enabled: boolean) {
	const sentinel = useRef<HTMLTableRowElement>(null);
	useEffect(() => {
		const element = sentinel.current;
		if (!enabled || element === null) {
			return;
		}
		const observer = new IntersectionObserver((entries) => {
			if (entries.some((entry) => entry.isIntersecting)) {
				onInView();
			}
		});
		observer.observe(element);
		return () => observer.disconnect();
	}, [enabled, onInView]);
	return sentinel;
}
