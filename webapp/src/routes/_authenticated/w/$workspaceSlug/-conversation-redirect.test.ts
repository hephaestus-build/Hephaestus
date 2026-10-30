import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";

import { ROUTER_SEARCH } from "@/lib/router-search";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";
import { workspaceRewrite } from "@/runtime/workspace-address";
import { MentorFake } from "@/test/mentor-server";

vi.setConfig({ testTimeout: 30_000 });

const thread = "65ee0cb0-99dd-4b0f-86cb-bc8bfb5bbbed";
const entry = "e9999999-0000-4000-8000-000000000000";

it.each([
	{ origin: "https://example.com", path: "/w/acme/mentor", destination: "/w/acme/conversation" },
	{ origin: "https://acme.example.com", path: "/mentor", destination: "/conversation" },
])("keeps query and fragment links from $path", async ({ origin, path, destination }) => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/workspaces/acme/members/me", () => HttpResponse.json(ownMembership("MEMBER"))),
		...new MentorFake().handlers(),
	);
	const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	const history = createMemoryHistory({
		initialEntries: [`${path}?thread=${thread}&entry=${entry}#reply`],
	});
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree,
		origin,
		history,
		rewrite: workspaceRewrite(
			{ enabled: true, baseDomain: "example.com", apexOrigin: "https://example.com" },
			origin,
		),
		context: { queryClient, auth: undefined },
	});
	await router.load();
	expect(router.state.location.pathname).toBe("/w/acme/conversation");
	expect(router.state.location.search).toMatchObject({ thread, entry });
	expect(router.state.location.hash).toBe("reply");
	expect(history.location.pathname).toBe(destination);
	expect(history.location.hash).toBe("#reply");
	expect(history).toHaveLength(1);
	queryClient.clear();
});
