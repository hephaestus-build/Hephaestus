import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { isRecord } from "@/lib/is-record";
import type { WorkspaceRole } from "@/lib/workspace-roles";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";

const WORKSPACE_HOME = "/w/acme";

// `router.load()` lazily imports each matched route's module, so a case pays its transform cost.
vi.setConfig({ testTimeout: 15_000 });

function newRouter(url?: string) {
	return createRouter({
		routeTree,
		...(url ? { history: createMemoryHistory({ initialEntries: [url] }) } : {}),
		context: {
			// A fresh client per case: a shared cache would let one role's answer satisfy another's guard.
			queryClient: new QueryClient(),
			auth: undefined,
		},
	});
}

// `routesById` is keyed by generated route id, and its value type does not survive `Object.values`,
// so `Object.values` widens to `any` and `fullPath` is narrowed on the way out rather than asserted.
const routePaths = Object.values(newRouter().routesById)
	.map((route) => (isRecord(route) ? route.fullPath : undefined))
	.filter((fullPath): fullPath is string => typeof fullPath === "string");
const adminUrls = routePaths
	.filter((fullPath) => fullPath.startsWith("/w/$workspaceSlug/admin/"))
	.map((fullPath) => fullPath.replace("$workspaceSlug", "acme"));

function mockMembership(role: WorkspaceRole | null) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			role
				? HttpResponse.json({ role, userId: 1, userLogin: "ada", userName: "Ada" })
				: // The server answers a non-member with 400, not 403.
					HttpResponse.json({ status: 400, title: "Bad Request" }, { status: 400 }),
		),
	);
}

async function land(url: string) {
	const router = newRouter(url);
	await router.load();
	return router.state.location.pathname;
}

/** Guards the bypass a route file cannot show: an /admin URL that does not nest under the gate. */
describe("workspace-admin route gate", () => {
	it("enumerates the admin routes rather than trusting a hand-written list", () => {
		// A filter that matched nothing would leave every case below vacuously green.
		expect(adminUrls.length).toBeGreaterThanOrEqual(19);
		expect(adminUrls).toContain("/w/acme/admin/settings");
	});

	it.each([
		"/w/$workspaceSlug/achievements",
		"/w/$workspaceSlug/user/$username/achievements",
		"/w/$workspaceSlug/admin/achievements",
		"/w/$workspaceSlug/admin/achievement-designer",
	])("does not register the retired route %s", (path) => {
		expect(routePaths).not.toContain(path);
	});

	it.each(adminUrls)("redirects a MEMBER away from %s", async (url) => {
		mockMembership("MEMBER");
		expect(await land(url)).toBe(WORKSPACE_HOME);
	});

	it("admits an ADMIN", async () => {
		mockMembership("ADMIN");
		expect(await land("/w/acme/admin/settings")).toBe("/w/acme/admin/settings");
	});

	// Member onboarding is the one admin page that needs OWNER: the route's own `beforeLoad` sends
	// an ADMIN on to settings, which the layout gate above it cannot express.
	it("sends an ADMIN from member onboarding to settings", async () => {
		mockMembership("ADMIN");
		expect(await land("/w/acme/admin/onboarding")).toBe("/w/acme/admin/settings");
	});

	it("admits an OWNER to member onboarding", async () => {
		mockMembership("OWNER");
		expect(await land("/w/acme/admin/onboarding")).toBe("/w/acme/admin/onboarding");
	});

	it("redirects a non-member", async () => {
		mockMembership(null);
		expect(await land("/w/acme/admin/settings")).toBe(WORKSPACE_HOME);
	});

	it("redirects when the membership cannot be resolved", async () => {
		server.use(http.get("*/workspaces/:workspaceSlug/members/me", () => HttpResponse.error()));
		expect(await land("/w/acme/admin/settings")).toBe(WORKSPACE_HOME);
	});
});
