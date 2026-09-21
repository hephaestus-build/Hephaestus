import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { isRecord } from "@/lib/is-record";
import { hasText } from "@/lib/text";
import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";

// `router.load()` lazily imports each matched route's module, so a case pays its transform cost.
vi.setConfig({ testTimeout: 15_000 });

function newRouter(url?: string) {
	return createRouter({
		routeTree,
		...(hasText(url) ? { history: createMemoryHistory({ initialEntries: [url] }) } : {}),
		// A fresh client per case: a shared cache would let one role's answer satisfy another's guard.
		context: { queryClient: new QueryClient(), auth: undefined },
	});
}

// `routesById` is keyed by generated route id, and its value type does not survive `Object.values`,
// so `Object.values` widens to `any` and `fullPath` is narrowed on the way out rather than asserted.
const adminUrls = Object.values(newRouter().routesById)
	.map((route) => (isRecord(route) ? route.fullPath : undefined))
	.filter((fullPath): fullPath is string => typeof fullPath === "string")
	.filter((fullPath) => fullPath.startsWith("/admin/"));

function mockAppRole(appRole: "APP_ADMIN" | "APP_USER") {
	server.use(http.get("*/user", () => HttpResponse.json({ ...currentUser, appRole })));
}

async function land(url: string) {
	const router = newRouter(url);
	await router.load();
	return router.state.location.pathname;
}

/** Catches the bypass a route file cannot show: an /admin URL that does not nest under the gate. */
describe("instance-admin route gate", () => {
	it("enumerates the instance-admin routes rather than trusting a hand-written list", () => {
		// A filter that matched nothing would leave every case below vacuously green.
		expect(adminUrls.length).toBeGreaterThanOrEqual(6);
		expect(adminUrls).toContain("/admin/users");
		expect(adminUrls).toContain("/admin/usage");
	});

	it.each(adminUrls)("redirects a non-admin away from %s", async (url) => {
		mockAppRole("APP_USER");
		await expect(land(url)).resolves.toBe("/");
	});

	it("admits an APP_ADMIN", async () => {
		mockAppRole("APP_ADMIN");
		await expect(land("/admin/users")).resolves.toBe("/admin/users");
	});
});

/** Both consoles reuse page names, so the title is all that separates two open admin tabs. */
describe("admin tab titles", () => {
	async function titleOf(url: string) {
		mockAppRole("APP_ADMIN");
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
		);
		const router = newRouter(url);
		await router.load();
		const titlesOutsideIn = router.state.matches
			.flatMap((match) => match.meta ?? [])
			.map((tag) => tag?.title)
			.filter((title): title is string => typeof title === "string");
		return titlesOutsideIn.at(-1);
	}

	it.each([
		["/admin/usage", "AI usage · Instance admin · Hephaestus"],
		["/w/hephaestus/admin/usage", "AI usage · Admin · Hephaestus"],
	])("distinguishes %s in the tab title", async (url, title) => {
		await expect(titleOf(url)).resolves.toBe(title);
	});
});
