import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	Outlet,
	RouterProvider,
	useLocation,
} from "@tanstack/react-router";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import Header from "@/components/layout/Header";
import { AppSidebar } from "@/components/layout/sidebar/AppSidebar";
import { SidebarProvider } from "@/components/ui/sidebar";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { ROUTER_SEARCH } from "@/lib/router-search";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { Route as rootDefinition } from "@/routes/__root";
import { Route as adminDefinition } from "@/routes/_authenticated/admin";
import { Route as settingsDefinition } from "@/routes/_authenticated/settings";
import { QUERY_STALE_TIME_MS } from "@/runtime/tanstack-query/query-defaults";

vi.mock("@/runtime/auth/AuthContext", () => ({
	useAuth: () => ({ isAuthenticated: true, isLoading: false }),
}));
vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => false }));

function Chrome() {
	const active = useActiveWorkspaceSlug();
	const { pathname } = useLocation();
	return (
		<SidebarProvider>
			<Header
				version="1.0.0"
				environmentName="test"
				isProduction
				session="signed-in"
				workspaceSlug={active.chromeWorkspaceSlug}
				name="Test developer"
				onLogin={vi.fn()}
				onLogout={vi.fn()}
				onRetrySession={vi.fn()}
			/>
			<AppSidebar
				isAdmin={false}
				isAppAdmin
				showConversation
				integrationKinds={[]}
				context={pathname.startsWith("/admin") ? "admin" : "main"}
				workspaces={active.workspaces}
				activeWorkspace={active.chromeWorkspace}
			/>
			<output>
				{[active.workspaceSlug, active.chromeWorkspaceSlug, active.providerType]
					.map(String)
					.join("|")}
			</output>
			<Outlet />
		</SidebarProvider>
	);
}

function renderNavigation(initialEntry = "/w/beta/conversation") {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { staleTime: QUERY_STALE_TIME_MS } },
	});
	queryClient.setQueryData(listWorkspacesOptions().queryKey, [
		workspaceListItem("alpha"),
		workspaceListItem("beta", { providerType: "GITLAB" }),
	]);
	const root = createRootRoute({
		component: Chrome,
		validateSearch: rootDefinition.options.validateSearch,
	});
	const workspace = createRoute({
		getParentRoute: () => root,
		path: "w/$workspaceSlug/mentor",
		component: () => null,
	});
	const workspaceHome = createRoute({
		getParentRoute: () => root,
		path: "w/$workspaceSlug",
		component: () => <h1>Workspace home</h1>,
	});
	const settings = createRoute({
		getParentRoute: () => root,
		path: "settings",
		component: () => <h1>User settings page</h1>,
		search: settingsDefinition.options.search,
	});
	const admin = createRoute({
		getParentRoute: () => root,
		path: "admin",
		component: Outlet,
		search: adminDefinition.options.search,
	});
	const adminIndex = createRoute({
		getParentRoute: () => admin,
		path: "/",
		component: () => <h1>Instance administration page</h1>,
	});
	const adminSettings = createRoute({
		getParentRoute: () => admin,
		path: "settings",
		component: () => <h1>Instance settings page</h1>,
	});
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree: root.addChildren([
			workspace,
			workspaceHome,
			settings,
			admin.addChildren([adminIndex, adminSettings]),
		]),
		history: createMemoryHistory({ initialEntries: [initialEntry] }),
	});
	const view = render(
		<QueryClientProvider client={queryClient}>
			<RouterProvider router={router} />
		</QueryClientProvider>,
	);
	return { router, ...view };
}

describe("workspace context on global routes", () => {
	it("keeps the selected second workspace when User settings is opened from its sidebar", async () => {
		const user = userEvent.setup();
		const { router } = renderNavigation();
		await screen.findByText("beta|beta|GITLAB");
		await user.click(screen.getByRole("link", { name: "User settings" }));
		await screen.findByRole("heading", { name: "User settings page" });
		expect(router.state.location.pathname).toBe("/settings");
		await waitFor(() =>
			expect(screen.getByRole("status").textContent).toBe("undefined|beta|GITLAB"),
		);
		expect(screen.getByRole("link", { name: "Your AI choice" }).getAttribute("href")).toContain(
			"/w/beta/onboarding",
		);
		expect(screen.getByRole("link", { name: "Activity" }).getAttribute("href")).toBe(
			"/w/beta/activity",
		);
	});
	it("preserves global context through admin subnavigation, history and a URL reload", async () => {
		const user = userEvent.setup();
		const view = renderNavigation();
		await screen.findByText("beta|beta|GITLAB");
		await user.click(screen.getByRole("link", { name: "Instance administration" }));
		await screen.findByRole("heading", { name: "Instance administration page" });
		await user.click(screen.getByRole("link", { name: "Instance settings" }));
		await screen.findByRole("heading", { name: "Instance settings page" });
		expect(view.router.state.location.search).toMatchObject({ chromeWorkspace: "beta" });
		await user.click(screen.getByRole("link", { name: "User settings" }));
		await screen.findByRole("heading", { name: "User settings page" });
		await act(async () => {
			view.router.history.back();
		});
		await screen.findByRole("heading", { name: "Instance settings page" });
		await act(async () => {
			view.router.history.forward();
		});
		await screen.findByRole("heading", { name: "User settings page" });
		const { href } = view.router.state.location;
		view.unmount();
		renderNavigation(href);
		await screen.findByText("undefined|beta|GITLAB");
		expect(screen.getByRole("link", { name: "Your AI choice" }).getAttribute("href")).toContain(
			"/w/beta/onboarding",
		);
	});

	it("keeps the selected workspace through the account menu's User settings", async () => {
		const user = userEvent.setup();
		renderNavigation();
		await screen.findByText("beta|beta|GITLAB");
		await user.click(screen.getByRole("button", { name: "Account" }));
		await user.click(await screen.findByRole("menuitem", { name: "User settings" }));
		await screen.findByRole("heading", { name: "User settings page" });
		await screen.findByText("undefined|beta|GITLAB");
		expect(screen.getByRole("link", { name: "Hephaestus home" }).getAttribute("href")).toBe(
			"/w/beta",
		);
	});

	it("returns from actual instance administration to the selected workspace", async () => {
		const user = userEvent.setup();
		const { router } = renderNavigation();
		await screen.findByText("beta|beta|GITLAB");
		await user.click(screen.getByRole("link", { name: "Instance administration" }));
		await screen.findByRole("heading", { name: "Instance administration page" });
		const back = screen.getByRole("link", { name: "Back to app" });
		expect(back.getAttribute("href")).toBe("/w/beta");
		await user.click(back);
		await screen.findByRole("heading", { name: "Workspace home" });
		expect(router.state.location.pathname).toBe("/w/beta");
		await screen.findByText("beta|beta|GITLAB");
	});

	it("keeps the normal app fallback when global context names no eligible membership", async () => {
		renderNavigation("/admin?chromeWorkspace=missing");
		await screen.findByRole("heading", { name: "Instance administration page" });
		expect(screen.getByRole("link", { name: "Back to app" }).getAttribute("href")).toBe("/");
	});
});
