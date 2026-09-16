import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	Outlet,
	RouterProvider,
} from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { toast } from "sonner";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { Route as workspaceHomeRoute } from "@/routes/_authenticated/w/$workspaceSlug/index";

import { useWorkspaceSwitcher } from "./use-workspace-switcher";

vi.mock("sonner", () => ({ toast: { info: vi.fn() } }));

function SwitchWorkspace() {
	const switchWorkspace = useWorkspaceSwitcher();
	return (
		<>
			<button
				type="button"
				onClick={() =>
					void switchWorkspace({ displayName: "Beta workspace", workspaceSlug: "beta" })
				}
			>
				Switch workspace
			</button>
			<button
				type="button"
				onClick={() =>
					void switchWorkspace({ displayName: "Alpha workspace", workspaceSlug: "alpha" })
				}
			>
				Keep workspace
			</button>
		</>
	);
}

/**
 * `mountAtRoot` puts the switcher above every match, which is where `__root.tsx` renders it —
 * the placement the whole portable branch rests on, since `to: "."` resolves from the deepest
 * match of the current location rather than from the calling component's route.
 */
function renderRoute(initialEntry: string, path: string, { mountAtRoot = false } = {}) {
	const rootRoute = createRootRoute({
		component: mountAtRoot
			? () => (
					<>
						<SwitchWorkspace />
						<Outlet />
					</>
				)
			: Outlet,
	});
	const workspaceRoute = createRoute({
		getParentRoute: () => rootRoute,
		path: "w/$workspaceSlug",
		component: Outlet,
	});
	const currentRoute = createRoute({
		getParentRoute: () => workspaceRoute,
		path,
		component: mountAtRoot ? () => null : SwitchWorkspace,
	});
	// The workspace home's own schema and search middleware, not a copy: which of its options survive
	// a switch is the thing under test, so a key added to the retain list is tested here too.
	const indexRoute = createRoute({
		getParentRoute: () => workspaceRoute,
		path: "/",
		component: () => null,
		validateSearch: workspaceHomeRoute.options.validateSearch,
		search: workspaceHomeRoute.options.search,
	});
	const router = createRouter({
		routeTree: rootRoute.addChildren([workspaceRoute.addChildren([indexRoute, currentRoute])]),
		history: createMemoryHistory({ initialEntries: [initialEntry] }),
	});

	render(<RouterProvider router={router} />);
	return router;
}

function renderGlobalRoute() {
	const rootRoute = createRootRoute({ component: Outlet });
	const settingsRoute = createRoute({
		getParentRoute: () => rootRoute,
		path: "settings",
		component: SwitchWorkspace,
	});
	const workspaceRoute = createRoute({
		getParentRoute: () => rootRoute,
		path: "w/$workspaceSlug",
		component: () => null,
	});
	const router = createRouter({
		routeTree: rootRoute.addChildren([settingsRoute, workspaceRoute]),
		history: createMemoryHistory({ initialEntries: ["/settings"] }),
	});

	render(<RouterProvider router={router} />);
	return router;
}

async function clickWorkspaceSwitch() {
	const user = userEvent.setup();
	await user.click(await screen.findByRole("button", { name: "Switch workspace" }));
}

describe("useWorkspaceSwitcher", () => {
	beforeEach(() => vi.clearAllMocks());

	it("keeps a portable route", async () => {
		const router = renderRoute("/w/alpha/teams?tab=members", "teams");

		await clickWorkspaceSwitch();

		await waitFor(() => expect(router.state.location.href).toBe("/w/beta/teams"));
		expect(toast.info).not.toHaveBeenCalled();
	});

	it("clears the leaderboard's team filter and keeps its workspace-independent options", async () => {
		const router = renderRoute(
			"/w/alpha?team=Backend&sort=LEAGUE_POINTS&mode=INDIVIDUAL",
			"teams",
			{
				mountAtRoot: true,
			},
		);

		await clickWorkspaceSwitch();

		await waitFor(() =>
			expect(router.state.location.href).toBe(
				"/w/beta?team=all&sort=LEAGUE_POINTS&mode=INDIVIDUAL",
			),
		);
	});

	it("keeps a portable route when the switcher is mounted above every match", async () => {
		const router = renderRoute("/w/alpha/teams?tab=members", "teams", { mountAtRoot: true });

		await clickWorkspaceSwitch();

		await waitFor(() => expect(router.state.location.href).toBe("/w/beta/teams"));
		expect(toast.info).not.toHaveBeenCalled();
	});

	it("switches back to the workspace it came from", async () => {
		const router = renderRoute("/w/alpha/teams?tab=members", "teams");
		const user = userEvent.setup();

		await clickWorkspaceSwitch();
		await waitFor(() => expect(router.state.location.href).toBe("/w/beta/teams"));
		await user.click(await screen.findByRole("button", { name: "Keep workspace" }));

		await waitFor(() => expect(router.state.location.href).toBe("/w/alpha/teams"));
	});

	it.each([
		["mentor thread", "/w/alpha/mentor/thread-1?message=foreign", "mentor/$threadId"],
		["user profile", "/w/alpha/user/octocat?group=foreign", "user/$username"],
		[
			"practice",
			"/w/alpha/admin/practices/testing?status=foreign",
			"admin/practices/$practiceSlug",
		],
	])("falls back to workspace home from a %s", async (_name, initialEntry, path) => {
		const router = renderRoute(initialEntry, path);

		await clickWorkspaceSwitch();

		// The workspace home writes its own defaults into the URL; nothing of the previous page's.
		await waitFor(() =>
			expect(router.state.location.href).toBe("/w/beta?team=all&sort=SCORE&mode=INDIVIDUAL"),
		);
		expect(toast.info).toHaveBeenCalledExactlyOnceWith("Switched to Beta workspace", {
			description:
				"This page is specific to the previous workspace, so Hephaestus opened the new workspace's home page.",
		});
	});

	it("does nothing when the selected workspace is already active", async () => {
		const router = renderRoute("/w/alpha/mentor/thread-1?message=current", "mentor/$threadId");
		const user = userEvent.setup();

		await user.click(await screen.findByRole("button", { name: "Keep workspace" }));

		expect(router.state.location.href).toBe("/w/alpha/mentor/thread-1?message=current");
		expect(toast.info).not.toHaveBeenCalled();
	});

	it("opens workspace home silently from a global route", async () => {
		const router = renderGlobalRoute();

		await clickWorkspaceSwitch();

		await waitFor(() => expect(router.state.location.href).toBe("/w/beta"));
		expect(toast.info).not.toHaveBeenCalled();
	});
});
