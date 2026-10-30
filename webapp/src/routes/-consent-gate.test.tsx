import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { ROUTER_SEARCH } from "@/lib/router-search";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { consentOutstanding, unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";
import { consentIsPending } from "@/runtime/auth/guard";

// `router.load()` lazily imports each matched route's module, so a case pays its transform cost.
vi.setConfig({ testTimeout: 15_000 });

const DEEP_LINK = "/w/acme/conversation?thread=65ee0cb0-99dd-4b0f-86cb-bc8bfb5bbbed#reply";

function newClient() {
	return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

async function land(url: string) {
	server.use(http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])));
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree,
		history: createMemoryHistory({ initialEntries: [url] }),
		context: { queryClient: new QueryClient(), auth: undefined },
	});
	await router.load();
	return router.state.location;
}

describe("consent gate", () => {
	it("lets the application load once the notice has been answered", async () => {
		await expect(consentIsPending(newClient())).resolves.toBe(false);
	});

	it("holds the application back while the notice is outstanding", async () => {
		server.use(...consentOutstanding);
		await expect(consentIsPending(newClient())).resolves.toBe(true);
	});

	it("decides from the identity, so no read leaves before the answer is known", async () => {
		const requested: string[] = [];
		const record = ({ request }: { request: Request }) => {
			requested.push(new URL(request.url).pathname);
		};
		server.use(...consentOutstanding);
		server.events.on("request:start", record);
		try {
			await expect(consentIsPending(newClient())).resolves.toBe(true);
		} finally {
			server.events.removeListener("request:start", record);
		}
		expect(requested).toStrictEqual(["/user"]);
	});

	it("never asks on behalf of a signed-out visitor", async () => {
		let asked = false;
		server.use(
			unauthenticatedUser,
			http.get("*/user/consent", () => {
				asked = true;
				return HttpResponse.json({ completed: false });
			}),
		);

		await expect(consentIsPending(newClient())).resolves.toBe(false);
		expect(asked).toBe(false);
	});
});

describe("authenticated route gate", () => {
	it("masks the outstanding notice with the requested destination", async () => {
		server.use(...consentOutstanding);

		const location = await land(DEEP_LINK);

		expect(location.pathname).toBe("/consent");
		expect(location.search).toMatchObject({ returnTo: DEEP_LINK });
		expect(location.maskedLocation?.href).toBe(DEEP_LINK);
	});

	it("keeps the recoverable notice and destination when it cannot be loaded", async () => {
		server.use(
			http.get("*/user/consent", () => HttpResponse.error()),
			...consentOutstanding,
		);

		const location = await land(DEEP_LINK);
		expect(location.pathname).toBe("/consent");
		expect(location.maskedLocation?.href).toBe(DEEP_LINK);
	});
});
