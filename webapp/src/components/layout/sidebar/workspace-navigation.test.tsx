import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";
import { ROUTER_SEARCH } from "@/lib/router-search";

import { NavWorkspace } from "./NavWorkspace";
import { NavYou } from "./NavYou";

const paths = [
	"practice-profile",
	"activity",
	"conversation",
	"practices-across-the-workspace",
	"workspace-activity",
	"teams",
] as const;
function renderNavigation(initialEntry: string, practicesEnabled = true) {
	const root = createRootRoute({
		component: () => (
			<SidebarProvider>
				<NavYou workspaceSlug="acme" practicesEnabled={practicesEnabled} showMentor />
				<NavWorkspace workspaceSlug="acme" practicesEnabled={practicesEnabled} />
			</SidebarProvider>
		),
	});
	const children = paths.map((path) =>
		createRoute({
			getParentRoute: () => root,
			path: `w/$workspaceSlug/${path}`,
			component: () => null,
		}),
	);
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree: root.addChildren(children),
		history: createMemoryHistory({ initialEntries: [initialEntry] }),
	});
	render(<RouterProvider router={router} />);
}
describe("workspace navigation", () => {
	it.each([
		["practice-profile?detail=observation", "Practice profile"],
		["activity", "Activity"],
		["conversation?thread=11111111-1111-4111-8111-111111111111", "Ask Hephaestus"],
		["practices-across-the-workspace", "Practices across the workspace"],
		["workspace-activity", "Workspace activity"],
		["teams", "Teams"],
	])("marks exactly the current destination for %s", async (path, label) => {
		renderNavigation(`/w/acme/${path}`);
		await screen.findByRole("link", { name: label });
		await waitFor(() => expect(screen.getAllByRole("link", { current: "page" })).toHaveLength(1));
		const current = screen.getByRole("link", { current: "page" });
		expect(current.textContent).toBe(label);
		expect(Object.hasOwn(current.dataset, "active")).toBe(true);
	});
	it("keeps personal and workspace pages in separate named groups with practices off", async () => {
		renderNavigation("/w/acme/activity", false);
		const personal = within(await screen.findByRole("list", { name: "You" }));
		const workspace = within(screen.getByRole("list", { name: "Workspace" }));
		expect(personal.getAllByRole("link").map((link) => link.textContent)).toStrictEqual([
			"Activity",
			"Ask Hephaestus",
		]);
		expect(workspace.getAllByRole("link").map((link) => link.textContent)).toStrictEqual([
			"Workspace activity",
			"Teams",
		]);
	});
});
