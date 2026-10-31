import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";
import { ROUTER_SEARCH } from "@/lib/router-search";

import { NavSuperAdmin } from "./NavSuperAdmin";

/** Every instance admin page and the entry that leads to it. */
const DESTINATIONS: [path: string, entry: string][] = [
	["/admin", "Overview"],
	["/admin/users", "Users"],
	["/admin/person-data", "Person data"],
	["/admin/research", "Research datasets"],
	["/admin/workspaces", "Workspaces"],
	["/admin/workspaces/acme/users", "Workspaces"],
	["/admin/login-providers", "Login providers"],
	["/admin/catalog", "Practice catalog"],
	["/admin/feedback", "Inbox"],
	["/admin/models", "AI models"],
	["/admin/usage", "AI usage"],
	["/admin/audit", "Audit log"],
	["/admin/settings", "Instance settings"],
];

vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => false }));

function renderNavigation(initialEntry: string, defaultOpen: boolean) {
	const rootRoute = createRootRoute({
		component: () => (
			<SidebarProvider defaultOpen={defaultOpen}>
				<NavSuperAdmin />
			</SidebarProvider>
		),
	});
	const routes = DESTINATIONS.map(([path]) =>
		createRoute({ getParentRoute: () => rootRoute, path, component: () => null }),
	);
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree: rootRoute.addChildren(routes),
		history: createMemoryHistory({ initialEntries: [initialEntry] }),
	});

	render(<RouterProvider router={router} />);
}

describe("NavSuperAdmin", () => {
	it.each(
		(["expanded", "folded"] as const).flatMap((state) =>
			DESTINATIONS.map(([path, current]) => ({ path, state, current })),
		),
	)("marks only $current as current on $path, $state", async ({ path, state, current }) => {
		renderNavigation(path, state === "expanded");
		await screen.findByRole("link", { name: "Overview" });

		await waitFor(() =>
			expect(
				screen.queryAllByRole("link", { current: "page" }).map((link) => link.textContent),
			).toStrictEqual([current]),
		);
	});
});
