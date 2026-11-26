import type { AnyRouter } from "@tanstack/react-router";

/**
 * Starts downloading the code of every route that a load matches, when the load starts. The router
 * downloads a route's split code only after each `beforeLoad` above the route has answered. On a cold
 * load, the code of the page would otherwise wait for the identity and workspace reads, one round
 * trip after them. The router caches each download, so its own later request reuses this one.
 *
 * A guard that redirects leaves one page's code downloaded and unused.
 * Returns the function that stops the downloads.
 */
export function installEarlyRouteCode(
	router: Pick<AnyRouter, "subscribe" | "getMatchedRoutes" | "loadRouteChunk">,
): () => void {
	return router.subscribe("onBeforeLoad", ({ toLocation }) => {
		const [routes] = router.getMatchedRoutes(toLocation.pathname);
		for (const route of routes) {
			// The router's own load requests the same code again and reports a failure there.
			router.loadRouteChunk(route)?.catch(() => undefined);
		}
	});
}
