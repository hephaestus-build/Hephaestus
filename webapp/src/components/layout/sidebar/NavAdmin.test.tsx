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
import { ROUTER_SEARCH } from "@/lib/router-search";

import { NavAdmin } from "./NavAdmin";

const PATHS = [
	"w/$workspaceSlug/admin/settings",
	"w/$workspaceSlug/admin/members",
	"w/$workspaceSlug/admin/teams",
	"w/$workspaceSlug/admin/onboarding",
	"w/$workspaceSlug/admin/practices",
	"w/$workspaceSlug/admin/practices/releases",
	"w/$workspaceSlug/admin/practices/review",
	"w/$workspaceSlug/admin/practices/reviews",
	"w/$workspaceSlug/admin/practices/reviews/overview",
	"w/$workspaceSlug/admin/practices/reviews/feedback",
	"w/$workspaceSlug/admin/practices/reviews/problems",
	"w/$workspaceSlug/admin/practices/reviews/results",
	"w/$workspaceSlug/admin/practices/scans",
	"w/$workspaceSlug/admin/practices/scans/$scanId",
	"w/$workspaceSlug/admin/integrations",
	"w/$workspaceSlug/admin/integrations/scm",
	"w/$workspaceSlug/admin/integrations/slack",
	"w/$workspaceSlug/admin/integrations/outline",
	"w/$workspaceSlug/admin/models",
	"w/$workspaceSlug/admin/usage",
	"w/$workspaceSlug/admin/audit",
] as const;

/** Every admin page, the entry that leads to it, and the section that entry sits in. */
const DESTINATIONS: [path: string, entry: string, section?: string][] = [
	["/w/acme/admin/settings", "Workspace settings"],
	["/w/acme/admin/members", "Members"],
	["/w/acme/admin/teams", "Team setup"],
	["/w/acme/admin/onboarding", "Member onboarding"],
	["/w/acme/admin/practices", "Practice setup", "Practices"],
	["/w/acme/admin/practices/releases", "Practice setup", "Practices"],
	["/w/acme/admin/practices/review", "Review settings", "Practices"],
	// Both directions, because the two paths differ by one trailing character and a matcher that
	// compared strings rather than whole segments would light both entries up on either.
	["/w/acme/admin/practices/reviews", "Practice reviews", "Practices"],
	["/w/acme/admin/practices/reviews/overview", "Practice reviews", "Practices"],
	["/w/acme/admin/practices/reviews/feedback", "Practice reviews", "Practices"],
	["/w/acme/admin/practices/reviews/problems", "Practice reviews", "Practices"],
	["/w/acme/admin/practices/reviews/results", "Practice reviews", "Practices"],
	["/w/acme/admin/practices/scans", "Scans", "Practices"],
	["/w/acme/admin/practices/scans/6f1c2a7e-0d4b-4a8e-9b51-1a2b3c4d5e6f", "Scans", "Practices"],
	["/w/acme/admin/integrations", "Overview", "Integrations"],
	["/w/acme/admin/integrations/scm", "GitHub", "Integrations"],
	["/w/acme/admin/integrations/slack", "Slack", "Integrations"],
	["/w/acme/admin/integrations/outline", "Outline", "Integrations"],
	["/w/acme/admin/models", "AI models"],
	["/w/acme/admin/usage", "AI usage"],
	["/w/acme/admin/audit", "Audit log"],
];

function leaveAsRendered() {
	// The sidebar state comes from `defaultOpen` alone.
}

function closeOpenSections() {
	for (const toggle of screen.queryAllByRole("button", { expanded: true })) {
		fireEvent.click(toggle);
	}
}

/** Where an entry is out of sight, its section is the current page's entry instead. */
const SIDEBAR_STATES = [
	{ state: "expanded", open: true, arrange: leaveAsRendered, current: (entry: string) => entry },
	{
		state: "folded",
		open: false,
		arrange: leaveAsRendered,
		current: (entry: string, section?: string) => section ?? entry,
	},
	{
		state: "section closed",
		open: true,
		arrange: closeOpenSections,
		current: (entry: string, section?: string) => section ?? entry,
	},
];

let mobile = false;
vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => mobile }));

function renderNavigation(initialEntry: string, defaultOpen = true) {
	const rootRoute = createRootRoute({
		component: () => (
			<SidebarProvider defaultOpen={defaultOpen}>
				<NavAdmin workspaceSlug="acme" isOwner integrationKinds={["GITHUB", "SLACK", "OUTLINE"]} />
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
		...ROUTER_SEARCH,
		routeTree: rootRoute.addChildren(routes),
		history: createMemoryHistory({ initialEntries: [initialEntry] }),
	});

	render(<RouterProvider router={router} />);
}

describe("NavAdmin", () => {
	beforeEach(() => {
		mobile = false;
	});

	it.each(
		SIDEBAR_STATES.flatMap(({ state, open, arrange, current }) =>
			DESTINATIONS.map(([path, entry, section]) => ({
				path,
				state,
				open,
				arrange,
				current: current(entry, section),
			})),
		),
	)("marks only $current as current on $path, $state", async ({ path, open, arrange, current }) => {
		renderNavigation(path, open);
		await screen.findByRole("list", { name: "Administration" });
		arrange();

		await waitFor(() =>
			expect(
				[
					...screen.queryAllByRole("link", { current: "page" }),
					...screen.queryAllByRole("button", { current: "page" }),
				].map((element) => element.textContent),
			).toStrictEqual([current]),
		);
	});

	// The count beside Practice reviews is feedback awaiting approval, and the overview is the tab
	// that holds the approval queue.
	it("opens Practice reviews on the overview", async () => {
		renderNavigation("/w/acme/admin/settings");
		fireEvent.click(await screen.findByRole("button", { name: "Practices" }));

		const reviews = await screen.findByRole<HTMLAnchorElement>("link", {
			name: "Practice reviews",
		});
		expect(new URL(reviews.href).pathname).toBe("/w/acme/admin/practices/reviews/overview");
	});

	it("opens the folded Practices section on the overview", async () => {
		renderNavigation("/w/acme/admin/practices/reviews/feedback", false);

		const practices = await screen.findByRole<HTMLAnchorElement>("link", { name: "Practices" });
		expect(new URL(practices.href).pathname).toBe("/w/acme/admin/practices/reviews/overview");
	});

	it("keeps the active section visible when its children are collapsed", async () => {
		renderNavigation("/w/acme/admin/practices/review");

		await screen.findByRole("link", { name: "Review settings" });
		const practices = screen.getByRole("button", { name: "Practices" });
		fireEvent.click(practices);

		await waitFor(() => expect(screen.queryByRole("link", { name: "Review settings" })).toBeNull());
		expect(Object.hasOwn(practices.dataset, "active")).toBe(true);
	});

	it("offers four destinations under Practices", async () => {
		renderNavigation("/w/acme/admin/practices");
		await screen.findByRole("link", { name: "Practice setup" });
		const submenu = within(screen.getByRole("list", { name: "Practices" }));

		// Operations first: what the reviews need from an admin is the visit that recurs. Scans are a
		// periodic report, so they come after the setup and the settings.
		expect(submenu.getAllByRole("link").map((link) => link.textContent)).toStrictEqual([
			"Practice reviews",
			"Practice setup",
			"Review settings",
			"Scans",
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
