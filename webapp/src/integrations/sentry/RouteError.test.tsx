import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { expect, it, vi } from "vitest";

import { RouteError } from "./RouteError";

const { captureException } = vi.hoisted(() => ({ captureException: vi.fn() }));

vi.mock("@/integrations/sentry", () => ({ captureException }));

it("reports a thrown route error once and renders recovery controls", async () => {
	const error = new Error("failed route");
	const onCaughtError = vi.fn();
	using routeWarning = vi.spyOn(console, "warn").mockImplementation((...args: unknown[]) => {
		expect(args).toStrictEqual(["Warning: Error in route match: //"]);
	});
	const rootRoute = createRootRoute();
	const route = createRoute({
		getParentRoute: () => rootRoute,
		path: "/",
		component: () => {
			throw error;
		},
	});
	const router = createRouter({
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

	expect((await screen.findByRole("alert")).textContent).toContain("Something went wrong");
	expect(captureException).toHaveBeenCalledExactlyOnceWith(error);

	await userEvent.click(screen.getByRole("button", { name: "Try again" }));
	await vi.waitFor(() => expect(captureException).toHaveBeenCalledTimes(2));
	expect(onCaughtError).toHaveBeenCalledTimes(2);
	expect(routeWarning).toHaveBeenCalledTimes(2);
	for (const [caught] of onCaughtError.mock.calls) {
		expect(caught).toBe(error);
	}
});
