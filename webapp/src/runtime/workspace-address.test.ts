import {
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
} from "@tanstack/react-router";
import { describe, expect, it, vi } from "vitest";

import { installWorkspaceNavigation, tenantSlug, workspaceRewrite } from "./workspace-address";

const config = { enabled: true, baseDomain: "example.com", apexOrigin: "https://example.com" };
const tenant = "https://acme.example.com";

function rewrite(direction: "input" | "output", path: string, origin = tenant, enabled = true) {
	const url = new URL(path, origin);
	const result = workspaceRewrite({ ...config, enabled }, origin)[direction]?.({ url });
	return new URL(result ?? url).href;
}

describe("workspace URL rewrites", () => {
	it.each([
		["/", "/w/acme"],
		["/activity", "/w/acme/activity"],
		["/mentor/thread?message=one#reply", "/w/acme/mentor/thread?message=one#reply"],
	])("maps tenant %s to %s", (path, expected) => {
		expect(rewrite("input", path)).toBe(`${tenant}${expected}`);
	});
	it("maps tenant administration to its workspace, while instance admin links leave for the apex", () => {
		expect(rewrite("input", "/admin/settings")).toBe(`${tenant}/w/acme/admin/settings`);
		expect(rewrite("output", "/w/acme/admin/settings")).toBe(`${tenant}/admin/settings`);
		expect(rewrite("output", "/admin/settings")).toBe(`${config.apexOrigin}/admin/settings`);
	});
	it("preserves the query and fragment in both directions", () => {
		expect(rewrite("output", "/w/acme/activity?range=1y#people")).toBe(
			`${tenant}/activity?range=1y#people`,
		);
		expect(rewrite("output", "/w/acme")).toBe(`${tenant}/`);
	});
	it.each([
		"/login",
		"/consent",
		"/auth/callback",
		"/imprint",
		"/privacy",
		"/terms",
		"/unsubscribe",
		"/settings",
		"/integrations",
		"/w/other/activity",
	])("keeps %s on the apex", (path) => {
		expect(rewrite("input", path)).toBe(`${tenant}${path}`);
		expect(rewrite("output", path)).toBe(`${config.apexOrigin}${path}`);
	});
	it.each(["/", "/w/acme/activity", "/login"])("does not rewrite apex path %s", (path) => {
		expect(rewrite("input", path, config.apexOrigin)).toBe(`${config.apexOrigin}${path}`);
		expect(rewrite("output", path, config.apexOrigin)).toBe(`${config.apexOrigin}${path}`);
	});
	it.each(["input", "output"] as const)(
		"leaves URLs unchanged with the switch off: %s",
		(direction) => {
			expect(rewrite(direction, "/w/acme/activity?range=1y", tenant, false)).toBe(
				`${tenant}/w/acme/activity?range=1y`,
			);
		},
	);
	it.each([
		"https://acme.example.com.evil.test",
		"https://nested.acme.example.com",
		"https://example.com",
	])("does not identify %s as a tenant", (origin) => {
		expect(tenantSlug(new URL(origin), config)).toBeUndefined();
	});
});

function navigationRouter(origin: string, enabled = true) {
	const root = createRootRoute();
	const routeTree = root.addChildren([
		createRoute({ getParentRoute: () => root, path: "/login" }),
		createRoute({ getParentRoute: () => root, path: "/w/$workspaceSlug/activity" }),
	]);
	const router = createRouter({
		routeTree,
		origin,
		history: createMemoryHistory(),
		rewrite: workspaceRewrite({ ...config, enabled }, origin),
	});
	const navigate = vi.spyOn(router, "navigate").mockResolvedValue(undefined);
	router.history.subscribe(() => {
		void router.load();
	});
	installWorkspaceNavigation(router, { ...config, enabled });
	return { router, navigate };
}

describe("document navigation", () => {
	it("reloads a tenant-to-apex navigation", async () => {
		const { router, navigate } = navigationRouter(tenant);
		await router.commitLocation(router.buildLocation({ to: "/login" }));
		expect(navigate).toHaveBeenCalledWith(
			expect.objectContaining({ reloadDocument: true, href: "https://example.com/login" }),
		);
	});
	it("keeps same-workspace navigation client-side", async () => {
		const { router, navigate } = navigationRouter(tenant);
		await router.commitLocation(
			router.buildLocation({ to: "/w/$workspaceSlug/activity", params: { workspaceSlug: "acme" } }),
		);
		expect(navigate).not.toHaveBeenCalled();
		expect(router.history.location.pathname).toBe("/activity");
	});
	it("uses a document request for the apex sign-in return path", async () => {
		const { router, navigate } = navigationRouter(config.apexOrigin);
		await router.commitLocation(
			router.buildLocation({
				to: "/w/$workspaceSlug/activity",
				params: { workspaceSlug: "acme" },
				search: { range: "1y" },
			}),
		);
		expect(navigate).toHaveBeenCalledWith(
			expect.objectContaining({
				reloadDocument: true,
				href: "https://example.com/w/acme/activity?range=1y",
			}),
		);
	});
	it("does not change navigation with the switch off", async () => {
		const { router, navigate } = navigationRouter(tenant, false);
		await router.commitLocation(router.buildLocation({ to: "/login" }));
		expect(navigate).not.toHaveBeenCalled();
		expect(router.history.location.pathname).toBe("/login");
	});
});
