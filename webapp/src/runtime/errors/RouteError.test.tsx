import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { afterEach, expect, it, vi } from "vitest";

import { ROUTER_SEARCH } from "@/lib/router-search";
import { RouteError } from "./RouteError";

afterEach(() => {
	vi.unstubAllGlobals();
});

it("runs a failed route again on retry and shows the page once it renders", async () => {
	const error = new Error("failed route with private detail");
	const renderPage = vi
		.fn(() => <h1>Recovered page</h1>)
		.mockImplementation(() => {
			throw error;
		});
	const onCaughtError = vi.fn();
	const rootRoute = createRootRoute();
	const route = createRoute({
		getParentRoute: () => rootRoute,
		path: "/",
		component: renderPage,
	});
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree: rootRoute.addChildren([route]),
		history: createMemoryHistory({ initialEntries: ["/"] }),
		defaultErrorComponent: RouteError,
	});

	render(
		<StrictMode>
			<RouterProvider router={router} />
		</StrictMode>,
		{ onCaughtError },
	);

	const alert = await screen.findByRole("alert");
	expect(alert.textContent).toContain("We could not load this page");
	// The page names no error detail: a message can carry data the reader should not see.
	expect(document.body.textContent).not.toContain(error.message);

	// Retry runs the route again: a failure that persists is caught again, and the page still offers
	// a retry.
	const caughtBeforeRetry = onCaughtError.mock.calls.length;
	await userEvent.click(screen.getByRole("button", { name: "Retry" }));
	await vi.waitFor(() =>
		expect(onCaughtError.mock.calls.length).toBeGreaterThan(caughtBeforeRetry),
	);
	expect(screen.getByRole("alert").textContent).toContain("We could not load this page");

	renderPage.mockReturnValue(<h1>Recovered page</h1>);
	await userEvent.click(screen.getByRole("button", { name: "Retry" }));
	await screen.findByRole("heading", { name: "Recovered page" });
	expect(screen.queryByRole("alert")).toBeNull();
	for (const [caught] of onCaughtError.mock.calls) {
		expect(caught).toBe(error);
	}
});

function renderGuardedRoute(status: number, problem: object = {}) {
	const rootRoute = createRootRoute();
	const route = createRoute({
		getParentRoute: () => rootRoute,
		path: "/",
		beforeLoad: () => {
			throw Object.assign(new Error("private detail"), { status, ...problem });
		},
		component: () => <h1>Guarded page</h1>,
	});
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree: rootRoute.addChildren([route]),
		history: createMemoryHistory({ initialEntries: ["/"] }),
		defaultErrorComponent: RouteError,
	});
	render(<RouterProvider router={router} />);
}

it.each([
	[404, "We could not find this page"],
	[403, "You do not have access to this page"],
])("names a %i from beforeLoad and offers the way home, not a retry", async (status, title) => {
	renderGuardedRoute(status);

	const heading = await screen.findByRole("heading", { level: 1 });
	expect(heading.textContent).toBe(title);
	expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
	expect(screen.getByRole("link", { name: "Go to the home page" }).getAttribute("href")).toBe("/");
	expect(document.body.textContent).not.toContain("private detail");
});

it("announces a refusal as an alert and a missing page as a page", async () => {
	renderGuardedRoute(403);
	await screen.findByRole("alert");
	cleanup();

	renderGuardedRoute(404);
	await screen.findByRole("heading", { level: 1 });
	expect(screen.queryByRole("alert")).toBeNull();
});

it("offers no retry for a request the server refused, which would be refused again", async () => {
	renderGuardedRoute(400);

	await screen.findByRole("alert");
	expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
});

it("names the reference of a failed request, so the reader can quote it", async () => {
	renderGuardedRoute(503, { requestId: "4bf92f3577b34da6a3ce929d0e0e4736" });

	await screen.findByRole("alert");
	expect(screen.getByText("4bf92f35").textContent).toBe("4bf92f35");
	screen.getByRole("button", { name: "Copy reference" });
});

it("names no reference for a failure without one, or with a value that is not a request id", async () => {
	renderGuardedRoute(503, { requestId: "not-an-id" });

	await screen.findByRole("alert");
	expect(screen.queryByRole("button", { name: "Copy reference" })).toBeNull();
});

it("offers a reload when a route's code cannot load", async () => {
	const missing = new TypeError(
		"Failed to fetch dynamically imported module: https://hephaestus.example/assets/members-1a2b3c.js",
	);
	const reload = vi.fn();
	// jsdom cannot reload, so the page's location is replaced by one that records the reload.
	vi.stubGlobal("location", { reload });
	const rootRoute = createRootRoute();
	const route = createRoute({
		getParentRoute: () => rootRoute,
		path: "/",
		// A loader that throws reaches the same error component as a route chunk that fails, without
		// TanStack Router's own automatic reload.
		loader: () => {
			throw missing;
		},
	});
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree: rootRoute.addChildren([route]),
		history: createMemoryHistory({ initialEntries: ["/"] }),
		defaultErrorComponent: RouteError,
	});
	render(<RouterProvider router={router} />, { onCaughtError: vi.fn() });

	await screen.findByRole("heading", { name: "This page needs a reload" });
	expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
	expect(document.body.textContent).not.toContain("members-1a2b3c");

	await userEvent.click(screen.getByRole("button", { name: "Reload" }));
	expect(reload).toHaveBeenCalledOnce();
});
