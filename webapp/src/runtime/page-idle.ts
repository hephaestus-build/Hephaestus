import type { QueryClient } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";

/**
 * How long nothing may load before the page counts as idle. A query that the last answer enables
 * starts in the render that answer causes, well within it.
 */
export const PAGE_IDLE_SETTLE_MS = 50;

/** The router's view of loading, which is all this needs of it. */
type RouterLike = Pick<AnyRouter, "state" | "subscribe">;

/**
 * Marks the document with `data-page-idle` while no first load of the page, no write and no route
 * load is pending, so that a browser test waits for the app's own readiness instead of guessing it
 * from the network (`webapp/e2e/screenshots/settled.ts`). Its value names the document and counts its
 * idle periods, so a test can confirm that the same page stayed idle between two reads, also across a
 * redirect to a new document whose count starts again.
 *
 * A first load is a read with no data yet that something on the page observes: a background refetch
 * of data on screen does not count, and neither does a preload for another page.
 *
 * It listens to the caches and the router directly, outside React: the caches call their listeners in
 * the same task that starts a fetch or a write, so the mark leaves before anything renders the load,
 * and no error boundary can stop it. The returned function removes the mark and stops listening.
 */
export function installPageIdle(router: RouterLike, queryClient: QueryClient): () => void {
	const root = document.documentElement;
	let idlePeriods = 0;
	let timer: ReturnType<typeof setTimeout> | undefined;
	const busy = () =>
		router.state.status === "pending" ||
		router.state.isLoading ||
		queryClient.isMutating() > 0 ||
		queryClient.isFetching({
			predicate: (query) => query.state.data === undefined && query.getObserversCount() > 0,
		}) > 0;
	const check = () => {
		if (busy()) {
			clearTimeout(timer);
			timer = undefined;
			delete root.dataset.pageIdle;
			return;
		}
		if (root.dataset.pageIdle !== undefined || timer !== undefined) {
			return;
		}
		timer = setTimeout(() => {
			timer = undefined;
			if (!busy()) {
				idlePeriods += 1;
				root.dataset.pageIdle = `${performance.timeOrigin}:${idlePeriods}`;
			}
		}, PAGE_IDLE_SETTLE_MS);
	};
	const stops = [
		queryClient.getQueryCache().subscribe(check),
		queryClient.getMutationCache().subscribe(check),
		router.subscribe("onBeforeLoad", check),
		router.subscribe("onLoad", check),
		router.subscribe("onResolved", check),
	];
	check();
	return () => {
		for (const stop of stops) {
			stop();
		}
		clearTimeout(timer);
		delete root.dataset.pageIdle;
	};
}
