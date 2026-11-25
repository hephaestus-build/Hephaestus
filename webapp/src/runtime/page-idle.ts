import { useIsFetching, useIsMutating } from "@tanstack/react-query";
import { useEffect } from "react";

/**
 * How long nothing may load before the page counts as idle. A query that the last answer enables
 * starts in the render that answer causes, well within it.
 */
export const PAGE_IDLE_SETTLE_MS = 50;

/**
 * Marks the document `data-page-idle="true"` while no first load, no write and no route load is
 * pending, so that a browser test waits for the app's own readiness instead of guessing it from the
 * network (`webapp/e2e/screenshots/settled.ts`). A background refetch of data already on screen does
 * not unmark it: the page shows its content during a poll.
 */
export function usePageIdle(routeLoading: boolean): void {
	const firstLoads = useIsFetching({ predicate: (query) => query.state.data === undefined });
	const writes = useIsMutating();
	const idle = firstLoads === 0 && writes === 0 && !routeLoading;
	useEffect(() => {
		const root = document.documentElement;
		if (!idle) {
			delete root.dataset.pageIdle;
			return;
		}
		const timer = setTimeout(() => {
			root.dataset.pageIdle = "true";
		}, PAGE_IDLE_SETTLE_MS);
		return () => clearTimeout(timer);
	}, [idle]);
}
