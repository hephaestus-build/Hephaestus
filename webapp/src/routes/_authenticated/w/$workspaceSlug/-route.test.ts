import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { listWorkspacesQueryKey } from "@/api/@tanstack/react-query.gen";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";

// `router.load()` lazily imports each matched route's module, so a case pays its transform cost.
vi.setConfig({ testTimeout: 15_000 });

const DEEP_LINK = "/w/foreign/mentor/thread-1?message=stale";
// The workspace home writes its own schema defaults into the URL it lands on.
const WORKSPACE_HOME = "/w/acme?team=all&sort=SCORE&mode=INDIVIDUAL";

function listWorkspaces(...slugs: string[]) {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json(slugs.map((slug) => workspaceListItem(slug)))),
	);
}

async function land(url: string, queryClient = new QueryClient()) {
	const router = createRouter({
		routeTree,
		history: createMemoryHistory({ initialEntries: [url] }),
		context: { queryClient, auth: undefined },
	});
	await router.load();
	return router.state.location.href;
}

/** The gate every `/w/<slug>/…` route inherits, driven through the generated tree. */
describe("workspace route gate", () => {
	it("opens a workspace the account can reach", async () => {
		listWorkspaces("acme");
		await expect(land("/w/acme")).resolves.toBe("/w/acme");
	});

	it("returns an inaccessible workspace's deep link to an accessible workspace home", async () => {
		listWorkspaces("acme");
		await expect(land(DEEP_LINK)).resolves.toBe(WORKSPACE_HOME);
	});

	it("returns to the home page when no workspace is accessible", async () => {
		listWorkspaces();
		await expect(land(DEEP_LINK)).resolves.toBe("/");
	});

	it("keeps the route when the workspace list cannot be fetched", async () => {
		server.use(http.get("*/workspaces", () => HttpResponse.error()));
		await expect(land(DEEP_LINK)).resolves.toBe(DEEP_LINK);
	});

	it("opens a just-created workspace the cache carries before the server lists it", async () => {
		listWorkspaces("acme");
		// The app's own `staleTime` (`integrations/tanstack-query/root-provider.tsx`), so the gate
		// answers from the list the creation wizard wrote rather than refetching past it.
		const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000 } } });
		queryClient.setQueryData(listWorkspacesQueryKey(), [
			workspaceListItem("acme"),
			workspaceListItem("brand-new"),
		]);

		await expect(land("/w/brand-new", queryClient)).resolves.toBe("/w/brand-new");
	});
});
