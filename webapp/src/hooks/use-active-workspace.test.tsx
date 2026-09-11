import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { act, render, screen } from "@testing-library/react";
import { describe, it, vi } from "vitest";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { QUERY_STALE_TIME_MS } from "@/integrations/tanstack-query/query-defaults";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";

import { useActiveWorkspaceSlug } from "./use-active-workspace";

vi.mock("@/integrations/auth/AuthContext", () => ({
	useAuth: () => ({ isAuthenticated: true, isLoading: false }),
}));

function ActiveWorkspace() {
	const { workspaceSlug, chromeWorkspaceSlug, chromeWorkspace, providerType } =
		useActiveWorkspaceSlug();
	return (
		<output>{`${workspaceSlug}|${chromeWorkspaceSlug}|${chromeWorkspace?.workspaceSlug}|${providerType}`}</output>
	);
}

function renderAt(initialEntry: string) {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { staleTime: QUERY_STALE_TIME_MS } },
	});
	queryClient.setQueryData(listWorkspacesOptions().queryKey, [
		workspaceListItem("alpha"),
		workspaceListItem("beta", { providerType: "GITLAB" }),
	]);
	const rootRoute = createRootRoute();
	const workspaceRoute = createRoute({
		getParentRoute: () => rootRoute,
		path: "w/$workspaceSlug",
		component: ActiveWorkspace,
	});
	const settingsRoute = createRoute({
		getParentRoute: () => rootRoute,
		path: "settings",
		component: ActiveWorkspace,
	});
	const router = createRouter({
		routeTree: rootRoute.addChildren([workspaceRoute, settingsRoute]),
		history: createMemoryHistory({ initialEntries: [initialEntry] }),
	});

	render(
		<QueryClientProvider client={queryClient}>
			<RouterProvider router={router} />
		</QueryClientProvider>,
	);
	return router;
}

describe("useActiveWorkspaceSlug", () => {
	it("derives the active workspace and provider from the route", async () => {
		const router = renderAt("/w/beta");

		await screen.findByText("beta|beta|beta|GITLAB");
		await act(() =>
			router.navigate({ to: "/w/$workspaceSlug", params: { workspaceSlug: "alpha" } }),
		);
		await screen.findByText("alpha|alpha|alpha|GITHUB");
	});

	it("leaves the route slug undefined on a route that names no workspace, and the chrome on the first", async () => {
		renderAt("/settings");

		await screen.findByText("undefined|alpha|alpha|GITHUB");
	});

	it("does not substitute another workspace for an unknown route slug", async () => {
		renderAt("/w/missing");

		await screen.findByText("missing|missing|undefined|GITHUB");
	});
});
