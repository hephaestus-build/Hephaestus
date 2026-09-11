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

import { AppSidebar } from "./AppSidebar";

vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => true }));

const workspace = {
	id: 1,
	workspaceSlug: "acme",
	accountLogin: "acme",
	displayName: "Acme",
	createdAt: new Date("2026-01-01T00:00:00Z"),
	providerType: "GITHUB",
	status: "ACTIVE",
	leaderboardEnabled: false,
	practicesEnabled: false,
	mentorEnabled: false,
	progressionEnabled: false,
	leaguesEnabled: false,
} as const;

function renderMobileSidebar() {
	const rootRoute = createRootRoute({
		component: () => (
			<SidebarProvider>
				<SidebarTrigger aria-label="Open navigation" />
				<AppSidebar
					username="ada"
					isAdmin={false}
					isAppAdmin={false}
					hasMentorAccess={false}
					integrationKinds={[]}
					context="main"
					workspaces={[workspace]}
					activeWorkspace={workspace}
				/>
			</SidebarProvider>
		),
	});
	const profileRoute = createRoute({
		getParentRoute: () => rootRoute,
		path: "w/$workspaceSlug/user/$username",
		component: () => null,
	});
	const router = createRouter({
		routeTree: rootRoute.addChildren([profileRoute]),
		history: createMemoryHistory({ initialEntries: ["/w/acme/user/ada"] }),
	});

	render(<RouterProvider router={router} />);
}

describe("AppSidebar on mobile", () => {
	it("closes when the current destination is selected", async () => {
		renderMobileSidebar();
		fireEvent.click(await screen.findByRole("button", { name: "Open navigation" }));

		const profile = await screen.findByRole("link", { name: "Profile" });
		fireEvent.click(profile);

		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	});
});
