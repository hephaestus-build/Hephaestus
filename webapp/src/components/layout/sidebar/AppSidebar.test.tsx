import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { ROUTER_SEARCH } from "@/lib/router-search";

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
	publishesPublicActivity: false,
} as const;

function renderMobileSidebar() {
	const rootRoute = createRootRoute({
		component: () => (
			<SidebarProvider>
				<SidebarTrigger aria-label="Open navigation" />
				<AppSidebar
					isAdmin={false}
					isAppAdmin={false}
					showConversation
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
	it("names both groups and returns focus to the opener after Escape", async () => {
		renderMobileSidebar();
		const opener = await screen.findByRole("button", { name: "Open navigation" });
		opener.focus();
		fireEvent.click(opener);
		const dialog = await screen.findByRole("dialog", { name: "Navigation" });
		expect(within(dialog).getByRole("list", { name: "You" })).not.toBeNull();
		expect(within(dialog).getByRole("list", { name: "Workspace" })).not.toBeNull();
		fireEvent.keyDown(dialog, { key: "Escape" });
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		await waitFor(() => expect(document.activeElement).toBe(opener));
	});
	it("closes when the current destination is selected", async () => {
		renderMobileSidebar();
		fireEvent.click(await screen.findByRole("button", { name: "Open navigation" }));

		const activity = await screen.findByRole("link", { name: "Activity" });
		fireEvent.click(activity);

		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	});
});
