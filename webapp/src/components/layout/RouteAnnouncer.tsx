import { useRouterState } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

/**
 * A single-page app swaps the page without a page load, so a screen reader says nothing when a link
 * is followed. This speaks the new page's title, which `<HeadContent>` has written by the time the
 * route's path has changed. A change of search or hash only reshapes the page the reader is on, so
 * it stays silent, as does the first render, which the browser announces itself.
 *
 * Focus stays where it is: the sidebar and the breadcrumbs outlive the navigation, and pulling
 * focus off them would restart a keyboard user's walk through them on every step.
 */
export function RouteAnnouncer() {
	const pathname = useRouterState({ select: (state) => state.resolvedLocation?.pathname });
	const announced = useRef(pathname);
	const region = useRef<HTMLDivElement>(null);

	useEffect(() => {
		if (pathname === undefined) {
			return;
		}
		if (announced.current !== undefined && announced.current !== pathname && region.current) {
			// Written to the node rather than held in state: the region exists to be spoken, not rendered.
			region.current.textContent = document.title;
		}
		announced.current = pathname;
	}, [pathname]);

	return <div ref={region} data-slot="route-announcer" aria-live="polite" className="sr-only" />;
}
