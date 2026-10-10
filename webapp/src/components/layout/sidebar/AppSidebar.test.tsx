import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { ROUTER_SEARCH } from "@/lib/search-params";

import { AppSidebar } from "./AppSidebar";

vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => true }));

const workspace = {
	id: 1,
	workspaceSlug: "acme",
	workspaceAddress: "https://hephaestus.build/w/acme",
	accountLogin: "acme",
	displayName: "Acme",
	createdAt: new Date("2026-01-01T00:00:00Z"),
	providerType: "GITHUB",
	status: "ACTIVE",
	practicesEnabled: false,
} as const;

function renderMobileSidebar() {
	const rootRoute = createRootRoute({
		component: () => (
			<SidebarProvider>
				<SidebarTrigger aria-label="Open navigation" />
				<AppSidebar
					isAdmin={false}
					isAppAdmin={false}
					showMentor
					integrationKinds={[]}
					context="main"
					workspaces={[workspace]}
					activeWorkspace={workspace}
				/>
			</SidebarProvider>
		),
	});
	const activityRoute = createRoute({
		getParentRoute: () => rootRoute,
		path: "w/$workspaceSlug/activity",
		component: () => null,
	});
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree: rootRoute.addChildren([activityRoute]),
		history: createMemoryHistory({ initialEntries: ["/w/acme/activity"] }),
	});

	render(<RouterProvider router={router} />);
}

describe("AppSidebar on mobile", () => {
	it("closes when the current destination is selected", async () => {
		renderMobileSidebar();
		fireEvent.click(await screen.findByRole("button", { name: "Open navigation" }));

		const activity = await screen.findByRole("link", { name: "Activity" });
		fireEvent.click(activity);

		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	});
});
