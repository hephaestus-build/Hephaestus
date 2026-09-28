import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";

import { NavAdmin } from "./NavAdmin";

const PATHS = [
	"w/$workspaceSlug/admin/settings",
	"w/$workspaceSlug/admin/practices",
	"w/$workspaceSlug/admin/practices/review",
	"w/$workspaceSlug/admin/practices/reviews",
	"w/$workspaceSlug/admin/integrations",
	"w/$workspaceSlug/admin/integrations/scm",
] as const;

let mobile = false;
vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => mobile }));

function renderNavigation(initialEntry: string, defaultOpen = true) {
	const rootRoute = createRootRoute({
		component: () => (
			<SidebarProvider defaultOpen={defaultOpen}>
				<NavAdmin workspaceSlug="acme" integrationKinds={["GITHUB", "SLACK", "OUTLINE"]} />
			</SidebarProvider>
		),
	});
	const routes = PATHS.map((path) =>
		createRoute({
			getParentRoute: () => rootRoute,
			path,
			component: () => null,
		}),
	);
	const router = createRouter({
		routeTree: rootRoute.addChildren(routes),
		history: createMemoryHistory({ initialEntries: [initialEntry] }),
	});

	render(<RouterProvider router={router} />);
}

describe("NavAdmin", () => {
	beforeEach(() => {
		mobile = false;
	});

	it.each([
		["/w/acme/admin/practices", "Practice setup"],
		["/w/acme/admin/practices/review", "Review settings"],
		// Both directions, because the two paths differ by one trailing character and a matcher that
		// compared strings rather than whole segments would light both entries up on either.
		["/w/acme/admin/practices/reviews", "Practice reviews"],
		["/w/acme/admin/integrations/scm", "GitHub"],
	])("marks only the destination for %s as current", async (path, currentLabel) => {
		renderNavigation(path);

		await screen.findByRole("link", { name: currentLabel });
		await waitFor(() => expect(screen.getAllByRole("link", { current: "page" })).toHaveLength(1));
		expect(screen.getByRole("link", { current: "page" }).textContent).toContain(currentLabel);
	});

	it("keeps the active section visible when its children are collapsed", async () => {
		renderNavigation("/w/acme/admin/practices/review");

		await screen.findByRole("link", { name: "Review settings" });
		const practices = screen.getByRole("button", { name: "Practices" });
		fireEvent.click(practices);

		await waitFor(() => expect(screen.queryByRole("link", { name: "Review settings" })).toBeNull());
		expect(Object.hasOwn(practices.dataset, "active")).toBe(true);
	});

	it("offers three destinations under Practices, not five", async () => {
		renderNavigation("/w/acme/admin/practices");
		await screen.findByRole("link", { name: "Practice setup" });
		const submenu = within(screen.getByRole("list", { name: "Practices" }));

		// Operations first: what the reviews need from an admin is the visit that recurs.
		expect(submenu.getAllByRole("link").map((link) => link.textContent)).toStrictEqual([
			"Practice reviews",
			"Practice setup",
			"Review settings",
		]);
	});

	it("keeps sections expandable on mobile when the desktop sidebar is collapsed", async () => {
		mobile = true;
		renderNavigation("/w/acme/admin/settings", false);

		const practices = await screen.findByRole("button", { name: "Practices" });
		fireEvent.click(practices);

		await screen.findByRole("link", { name: "Practice setup" });
	});
});
