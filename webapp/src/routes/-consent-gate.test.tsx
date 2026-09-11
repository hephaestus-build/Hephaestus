import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { consentIsPending } from "@/integrations/auth/guard";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";

// `router.load()` lazily imports each matched route's module, so a case pays its transform cost.
vi.setConfig({ testTimeout: 15_000 });

const DEEP_LINK = "/w/acme/mentor/thread-1?message=hi#reply";

describe("consent gate", () => {
	function newClient() {
		return new QueryClient({ defaultOptions: { queries: { retry: false } } });
	}

	function noticeAnswered(completed: boolean) {
		server.use(http.get("*/user/consent", () => HttpResponse.json({ completed })));
	}

	it("lets the application load once the notice has been answered", async () => {
		noticeAnswered(true);
		await expect(consentIsPending(newClient())).resolves.toBe(false);
	});

	it("holds the application back while the notice is outstanding", async () => {
		noticeAnswered(false);
		await expect(consentIsPending(newClient())).resolves.toBe(true);
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

	it("holds protected loaders back when the notice cannot be verified", async () => {
		server.use(http.get("*/user/consent", () => HttpResponse.error()));

		await expect(consentIsPending(newClient())).resolves.toBe(true);
	});
});

describe("authenticated route gate", () => {
	async function land(url: string) {
		server.use(http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])));
		const router = createRouter({
			routeTree,
			history: createMemoryHistory({ initialEntries: [url] }),
			context: { queryClient: new QueryClient(), auth: undefined },
		});
		await router.load();
		return router.state.location;
	}

	it("masks the outstanding notice with the requested destination", async () => {
		server.use(http.get("*/user/consent", () => HttpResponse.json({ completed: false })));

		const location = await land(DEEP_LINK);

		expect(location.pathname).toBe("/consent");
		expect(location.search).toMatchObject({ returnTo: DEEP_LINK });
		expect(location.maskedLocation?.href).toBe(DEEP_LINK);
	});

	it("keeps the recoverable notice and destination when it cannot be loaded", async () => {
		server.use(http.get("*/user/consent", () => HttpResponse.error()));

		const location = await land(DEEP_LINK);
		expect(location.pathname).toBe("/consent");
		expect(location.maskedLocation?.href).toBe(DEEP_LINK);
	});
});
