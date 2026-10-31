import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { expect, it, vi } from "vitest";

import { ROUTER_SEARCH } from "@/lib/router-search";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";
import { AuthProvider } from "@/runtime/auth/AuthContext";
import { ROUTE_RENDER_WAIT, testQueryClient } from "@/test/router-harness";

const { importFailure } = vi.hoisted(() => ({
	importFailure: new Error("Optional Hephaestus panel chunk unavailable"),
}));
vi.mock("./-GlobalConversationPanel", () => {
	throw importFailure;
});

it("keeps the main route available and offers a reload when the Hephaestus panel import rejects", async () => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([])),
		// Anyone may create a workspace here, so the empty page offers the action this test looks for.
		http.get("*/workspaces/providers", () => HttpResponse.json({ creationPolicy: "SELF_SERVICE" })),
		http.get("*/user/features", () => HttpResponse.json({})),
	);
	const queryClient = testQueryClient();
	const router = createRouter({
		...ROUTER_SEARCH,
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
				"You are not a member of any workspace yet.",
			);
		}, ROUTE_RENDER_WAIT);
		expect(
			within(screen.getByRole("main"))
				.getByRole("link", { name: "Create workspace" })
				.getAttribute("href"),
		).toBe("/workspaces/new");
		expect(screen.queryByText("We could not load this page")).toBeNull();
		// The browser keeps a failed import, so the message offers a page load, not a retry.
		await screen.findByText("We could not open Ask Hephaestus.");
		screen.getByRole("button", { name: "Reload page" });
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
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
