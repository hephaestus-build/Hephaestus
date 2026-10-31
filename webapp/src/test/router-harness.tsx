import { noop, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
	type AnyRouter,
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	Outlet,
	RouterProvider,
} from "@tanstack/react-router";
import { act, render } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, expect, vi } from "vitest";

import { ROUTER_SEARCH } from "@/lib/router-search";
import { routeTree } from "@/routeTree.gen";
import { AuthProvider } from "@/runtime/auth/AuthContext";

/**
 * A backstop against a route that never resolves, not a budget for how fast one should render. A
 * cold route mount costs ~1.5s alone and several seconds more when the whole file pool is running,
 * so a value close to the observed time turns saturation into a red build.
 */
export const ROUTE_RENDER_WAIT = { timeout: 30_000 } as const;

/**
 * The routers a test rendered. A navigation that a test starts and does not wait for still loads after
 * the test ends. `Transitioner` hands its commit to React, so a load that finishes once the file's
 * environment is gone rejects with "window is not defined", outside every test. After each test, the
 * hook below waits until these routers are idle, before Testing Library's cleanup unmounts the pages.
 * A load that does not settle fails the test that left it.
 *
 * Idle means no foreground load: background reloads and explicit preloads can still run.
 */
const rendered = new Set<AnyRouter>();

/**
 * How long the navigations that the test started may take, together, to settle after its last
 * assertion: the same backstop as `ROUTE_RENDER_WAIT`, because a page that a busy file pool loads can
 * take as long as a first render. The hook's own timeout stays above it.
 */
const ROUTE_SETTLE_WAIT = ROUTE_RENDER_WAIT;
const SETTLE_HOOK_TIMEOUT = ROUTE_SETTLE_WAIT.timeout + 5000;

/** No router loads in the foreground. */
function allIdle(routers: readonly AnyRouter[]) {
	const loading = routers.find((router) => router.state.status !== "idle");
	if (loading !== undefined) {
		throw new Error(`still loading ${loading.state.location.href}`);
	}
}

// Registered after Testing Library's cleanup, so it runs before it. A file's own `afterEach` that
// unmounts runs earlier still, and then no load can settle.
afterEach(async () => {
	const routers = [...rendered];
	rendered.clear();
	// Testing Library's own waits do the same: React commits outside `act` without a warning. Their
	// wrapper also drains a zero-delay timer, which never runs on Vitest's fake timers.
	const actEnvironment: unknown = Reflect.get(globalThis, "IS_REACT_ACT_ENVIRONMENT");
	Reflect.set(globalThis, "IS_REACT_ACT_ENVIRONMENT", false);
	try {
		// `vi.waitFor` advances Vitest's fake timers; Testing Library's `waitFor` does not.
		await vi
			.waitFor(() => {
				allIdle(routers);
			}, ROUTE_SETTLE_WAIT)
			.catch(noop);
		for (const router of routers) {
			// A soft failure, so that the cleanup hooks after this one still run.
			// oxlint-disable-next-line vitest/no-standalone-expect -- This hook belongs to every test that renders a route.
			expect
				.soft(
					router.state.status,
					`The router is still loading ${router.state.location.href} after the test. Wait for the page it opens, or answer the requests the test holds.`,
				)
				.toBe("idle");
		}
	} finally {
		Reflect.set(globalThis, "IS_REACT_ACT_ENVIRONMENT", actEnvironment);
	}
}, SETTLE_HOOK_TIMEOUT);

export function testQueryClient(): QueryClient {
	return new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
}

export function renderRouteAtWithRouter(
	path: string | string[],
	queryClient: QueryClient = testQueryClient(),
) {
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree,
		history: createMemoryHistory({ initialEntries: Array.isArray(path) ? path : [path] }),
		context: { queryClient, auth: undefined },
	});
	rendered.add(router);
	render(
		<QueryClientProvider client={queryClient}>
			<AuthProvider>
				<RouterProvider router={router} />
			</AuthProvider>
		</QueryClientProvider>,
	);
	return { queryClient, router };
}

export function renderRouteAt(path: string, queryClient: QueryClient = testQueryClient()) {
	return renderRouteAtWithRouter(path, queryClient).queryClient;
}

export async function renderWithRouter(node: ReactNode, initialPath: string) {
	const rootRoute = createRootRoute({
		component: () => (
			<>
				{node}
				<Outlet />
			</>
		),
	});
	const stubTree = rootRoute.addChildren([
		createRoute({ getParentRoute: () => rootRoute, path: "/", component: () => null }),
		createRoute({ getParentRoute: () => rootRoute, path: "$", component: () => null }),
	]);
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree: stubTree,
		history: createMemoryHistory({ initialEntries: [initialPath] }),
	});
	rendered.add(router);
	let result!: ReturnType<typeof render>;
	await act(async () => {
		result = render(<RouterProvider router={router} />);
		await router.load();
	});
	return { ...result, router };
}
