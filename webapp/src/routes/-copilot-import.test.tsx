import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { expect, it, vi } from "vitest";

import { AuthProvider } from "@/integrations/auth/AuthContext";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";
import { ROUTE_RENDER_WAIT, testQueryClient } from "@/test/router-harness";

const { importFailure } = vi.hoisted(() => ({
	importFailure: new Error("Optional copilot chunk unavailable"),
}));
vi.mock("./-GlobalCopilot", () => {
	throw importFailure;
});

it("keeps the main route available when the optional copilot import rejects", async () => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([])),
		http.get("*/user/features", () => HttpResponse.json({ MENTOR_ACCESS: true })),
	);
	const queryClient = testQueryClient();
	const router = createRouter({
		routeTree,
		history: createMemoryHistory({ initialEntries: ["/"] }),
		context: { queryClient, auth: undefined },
	});
	const onCaughtError = vi.fn();
	const view = render(
		<QueryClientProvider client={queryClient}>
			<AuthProvider>
				<RouterProvider router={router} />
			</AuthProvider>
		</QueryClientProvider>,
		{ onCaughtError },
	);

	try {
		await waitFor(() => expect(onCaughtError).toHaveBeenCalledOnce(), ROUTE_RENDER_WAIT);
		const caught: unknown = onCaughtError.mock.calls[0]?.[0];
		expect(caught).toMatchObject({ cause: importFailure });
		await waitFor(() => {
			expect(screen.getByRole("main").textContent).toContain(
				"You're not a member of any workspace yet.",
			);
		}, ROUTE_RENDER_WAIT);
		expect(
			within(screen.getByRole("main"))
				.getByRole("link", { name: "Create Workspace" })
				.getAttribute("href"),
		).toBe("/workspaces/new");
		expect(screen.queryByText("Something went wrong")).toBeNull();
	} finally {
		// This test creates its own QueryClient. Unmount observers before cancelling requests,
		// then release cache timers while React's environment is still available.
		await act(async () => {
			view.unmount();
			await queryClient.cancelQueries();
			queryClient.clear();
		});
	}
});
