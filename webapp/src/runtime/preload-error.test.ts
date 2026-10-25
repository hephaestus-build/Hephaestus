import {
	type AnyRouter,
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
} from "@tanstack/react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { installPreloadErrorReload } from "./preload-error";

const events: Event[] = [];

/** Routes use registered paths so navigation typechecks; `/about` stands for a route whose chunk is gone, and fails as Vite reports it, with a cancelable event. */
function staleRouter() {
	const rootRoute = createRootRoute();
	const tree = rootRoute.addChildren([
		createRoute({ getParentRoute: () => rootRoute, path: "/" }),
		createRoute({
			getParentRoute: () => rootRoute,
			path: "/about",
			loader: () => {
				const event = new Event("vite:preloadError", { cancelable: true });
				events.push(event);
				window.dispatchEvent(event);
			},
		}),
	]);
	return createRouter({
		routeTree: tree,
		defaultPreloadStaleTime: 0,
		history: createMemoryHistory({ initialEntries: ["/"] }),
	});
}

describe("installPreloadErrorReload", () => {
	const reload = vi.fn();
	let uninstall: () => void;
	let router: AnyRouter;

	beforeEach(async () => {
		sessionStorage.clear();
		events.length = 0;
		vi.stubGlobal("location", { reload });
		router = staleRouter();
		await router.load();
		uninstall = installPreloadErrorReload(router);
	});

	afterEach(() => {
		uninstall();
		reload.mockClear();
		vi.unstubAllGlobals();
		vi.restoreAllMocks();
	});

	it("does not reload for a preload on hover, and keeps the reload for the click", async () => {
		await router.preloadRoute({ to: "/about" });
		expect(reload).not.toHaveBeenCalled();
		await router.navigate({ to: "/about" });
		expect(reload).toHaveBeenCalledOnce();
	});

	it("reloads once for a navigation, then lets the error show", async () => {
		await router.navigate({ to: "/about" });
		await router.navigate({ to: "/" });
		await router.navigate({ to: "/about" });
		expect(reload).toHaveBeenCalledOnce();
		expect(events.map((event) => event.defaultPrevented)).toStrictEqual([true, false]);
	});

	it("keeps the error when the browser refuses storage", async () => {
		vi.spyOn(window, "sessionStorage", "get").mockImplementation(() => {
			throw new DOMException("blocked", "SecurityError");
		});
		await router.navigate({ to: "/about" });
		expect(reload).not.toHaveBeenCalled();
		expect(events[0]?.defaultPrevented).toBe(false);
	});
});
