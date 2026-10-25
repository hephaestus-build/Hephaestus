import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { expect, it, vi } from "vitest";

import { ROUTER_SEARCH } from "@/lib/router-search";
import { RouteError } from "./RouteError";

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

function renderGuardedRoute(status: number) {
	const rootRoute = createRootRoute();
	const route = createRoute({
		getParentRoute: () => rootRoute,
		path: "/",
		beforeLoad: () => {
			throw Object.assign(new Error("private detail"), { status });
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
	[400, "We could not load this page"],
])("names a %i from beforeLoad and offers the way home, not a retry", async (status, title) => {
	renderGuardedRoute(status);

	const alert = await screen.findByRole("alert");
	expect(within(alert).getByRole("heading", { level: 1 }).textContent).toBe(title);
	expect(within(alert).queryByRole("button", { name: "Retry" })).toBeNull();
	expect(within(alert).getByRole("link", { name: "Go to home page" }).getAttribute("href")).toBe(
		"/",
	);
	expect(document.body.textContent).not.toContain("private detail");
});
