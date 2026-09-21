import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	Outlet,
	RouterProvider,
} from "@tanstack/react-router";
import { act, render } from "@testing-library/react";
import type { ReactNode } from "react";

import { routeTree } from "@/routeTree.gen";
import { AuthProvider } from "@/runtime/auth/AuthContext";

/**
 * A backstop against a route that never resolves, not a budget for how fast one should render. A
 * cold route mount costs ~1.5s alone and several seconds more when the whole file pool is running,
 * so a value close to the observed time turns saturation into a red build.
 */
export const ROUTE_RENDER_WAIT = { timeout: 30_000 } as const;

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
		routeTree,
		history: createMemoryHistory({ initialEntries: Array.isArray(path) ? path : [path] }),
		context: { queryClient, auth: undefined },
	});
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
		routeTree: stubTree,
		history: createMemoryHistory({ initialEntries: [initialPath] }),
	});
	let result!: ReturnType<typeof render>;
	await act(async () => {
		result = render(<RouterProvider router={router} />);
		await router.load();
	});
	return { ...result, router };
}
