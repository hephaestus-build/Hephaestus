import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { afterEach, assert, expect, it, vi } from "vitest";

import { ROUTER_SEARCH } from "@/lib/router-search";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";
import { deferred } from "@/test/async";
import { testQueryClient } from "@/test/router-harness";

import { installEarlyRouteCode } from "./route-code";

afterEach(() => {
	vi.restoreAllMocks();
});

it("requests the page's code while the identity read is still open", async () => {
	const identity = deferred<Response>();
	let identityAnswered = false;
	server.use(
		http.get("*/user", async () => {
			const answer = await identity.promise;
			identityAnswered = true;
			return answer;
		}),
	);
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree,
		history: createMemoryHistory({ initialEntries: ["/w/acme/practice-profile"] }),
		context: { queryClient: testQueryClient(), auth: undefined },
	});
	const { component } =
		router.routesById["/_authenticated/w/$workspaceSlug/practice-profile"].options;
	assert(component?.preload, "The page's component is split into its own chunk");
	const requested = vi.spyOn(component, "preload");
	installEarlyRouteCode(router);

	const load = router.load();

	await vi.waitFor(() => {
		expect(requested).toHaveBeenCalledWith();
	});
	expect(identityAnswered).toBe(false);

	// A signed-out reader: the guard sends the load to sign-in, and it settles.
	identity.resolve(new HttpResponse(null, { status: 401 }));
	await load;
	expect(router.state.location.pathname).toBe("/login");
});
