import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { type HttpHandler, HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { Workspace } from "@/api/types.gen";
import { isRecord } from "@/lib/is-record";
import { ROUTER_SEARCH } from "@/lib/router-search";
import { hasText } from "@/lib/text";
import { currentUser } from "@/mocks/fixtures/auth";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";

// `router.load()` lazily imports each matched route's module, so a case pays its transform cost.
vi.setConfig({ testTimeout: 15_000 });

function newRouter(url?: string) {
	return createRouter({
		...ROUTER_SEARCH,
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

function appRoleHandler(appRole: "APP_ADMIN" | "APP_USER") {
	return http.get("*/user", () => HttpResponse.json({ ...currentUser, appRole }));
}

function mockAppRole(appRole: "APP_ADMIN" | "APP_USER") {
	server.use(appRoleHandler(appRole));
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

	it("rejects a non-admin at the tenant-local view-as route", async () => {
		mockAppRole("APP_USER");
		await expect(land("/w/hephaestus/view-as")).resolves.toBe("/");
	});
	it("admits an instance admin at the tenant-local view-as route", async () => {
		mockAppRole("APP_ADMIN");
		await expect(land("/w/hephaestus/view-as")).resolves.toBe("/w/hephaestus/view-as");
	});

	it("admits an APP_ADMIN", async () => {
		mockAppRole("APP_ADMIN");
		await expect(land("/admin/users")).resolves.toBe("/admin/users");
	});
});

/** The scoped details of a workspace, as an admin of it, or an instance admin, reads them. */
function workspaceDetails(kind: "GITHUB" | "GITLAB") {
	const listed = workspaceListItem("hephaestus", { providerType: kind });
	return {
		...listed,
		kind,
		gitlabWebhookRegistered: false,
		hasPersonalAccessToken: false,
		hasSlackToken: false,
		publicActivityEnabled: false,
		practiceReviewAutoTriggerEnabled: false,
		practiceReviewManualTriggerEnabled: false,
		updatedAt: listed.createdAt,
	} satisfies Workspace;
}

/** A workspace admin who is a member; `handlers` replace what a case changes. */
async function titleOf(url: string, ...handlers: HttpHandler[]) {
	mockAppRole("APP_ADMIN");
	server.use(
		// MSW answers with the first match, so a case's handlers go before the defaults.
		...handlers,
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("hephaestus")])),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
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

/** Both consoles reuse page names, so the title is all that separates two open admin tabs. */
describe("admin tab titles", () => {
	it.each([
		["/admin/usage", "AI usage · Instance administration · Hephaestus"],
		["/w/hephaestus/admin/usage", "AI usage · Admin · Hephaestus"],
	])("distinguishes %s in the tab title", async (url, title) => {
		await expect(titleOf(url)).resolves.toBe(title);
	});
});

/** A tab title repeats the name of the link that opened the page (WCAG 2.2 SC 2.4.2). */
describe("tab titles name the page the link opened", () => {
	it.each([
		["/admin", "Instance overview · Instance administration · Hephaestus"],
		["/w/hephaestus/admin/practices", "Practice setup · Admin · Hephaestus"],
		["/w/hephaestus/admin/practices/releases", "Catalog updates · Admin · Hephaestus"],
		["/w/hephaestus/admin/integrations/scm", "GitHub · Admin · Hephaestus"],
		["/w/hephaestus/onboarding", "Your AI choice · Hephaestus"],
	])("titles %s", async (url, title) => {
		await expect(titleOf(url)).resolves.toBe(title);
	});

	it("names GitLab on a GitLab workspace's source-control page", async () => {
		await expect(
			titleOf(
				"/w/hephaestus/admin/integrations/scm",
				http.get("*/workspaces", () =>
					HttpResponse.json([workspaceListItem("hephaestus", { providerType: "GITLAB" })]),
				),
			),
		).resolves.toBe("GitLab · Admin · Hephaestus");
	});

	it("names the provider from the workspace details when the workspace list could not load", async () => {
		await expect(
			titleOf(
				"/w/hephaestus/admin/integrations/scm",
				// Not an instance admin, so the workspace gate reads no details of its own.
				appRoleHandler("APP_USER"),
				http.get("*/workspaces", () => HttpResponse.json({ status: 503 }, { status: 503 })),
				http.get("*/workspaces/:workspaceSlug", () =>
					HttpResponse.json(workspaceDetails("GITLAB")),
				),
			),
		).resolves.toBe("GitLab · Admin · Hephaestus");
	});

	it("names GitLab for an instance admin who is not a member of the workspace", async () => {
		await expect(
			titleOf(
				"/w/hephaestus/admin/integrations/scm",
				http.get("*/workspaces", () => HttpResponse.json([])),
				http.get("*/workspaces/:workspaceSlug", () =>
					HttpResponse.json(workspaceDetails("GITLAB")),
				),
			),
		).resolves.toBe("GitLab · Admin · Hephaestus");
	});
});
