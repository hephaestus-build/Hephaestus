import { createRouter } from "@tanstack/react-router";
import { act, cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ROUTER_SEARCH } from "@/lib/router-search";
import { currentUser } from "@/mocks/fixtures/auth";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";
import {
	ACCOUNT_DELETED_NOTICE_LIFETIME_MS,
	recordAccountDeleted,
} from "@/runtime/auth/account-deleted-notice";
import { authClient } from "@/runtime/auth/auth-client";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

beforeEach(() => {
	// JSDOM has no viewport observer; the landing page uses it only for decorative motion.
	vi.stubGlobal("IntersectionObserver", ObserverStub);
	server.use(
		unauthenticatedUser,
		http.get("*/identity-providers", () =>
			HttpResponse.json([
				{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
			]),
		),
	);
});

afterEach(() => {
	vi.unstubAllGlobals();
	vi.restoreAllMocks();
});

describe("contextual sign-in", () => {
	it("keeps the page mounted with a shareable login URL, and supports back/forward and dismissal", async () => {
		const { router, queryClient } = renderRouteAtWithRouter("/about#team");
		const signIn = await screen.findByRole("button", { name: "Sign in" }, ROUTE_RENDER_WAIT);
		const content = screen.getByRole("heading", { name: "About Hephaestus" });
		await userEvent.click(signIn);
		await screen.findByRole("dialog");
		await screen.findByRole("button", { name: "Continue with GitHub" });
		expect(router.state.location.pathname).toBe("/about");
		expect(router.state.location.maskedLocation?.pathname).toBe("/login");
		expect(router.state.location.maskedLocation?.search).toMatchObject({ returnTo: "/about#team" });
		expect(screen.getByRole("heading", { name: "About Hephaestus", hidden: true })).toBe(content);
		// A new router models reloading the same history entry: temporary masking must not survive.
		const reloadedRouter = createRouter({
			...ROUTER_SEARCH,
			routeTree,
			history: router.history,
			context: { queryClient, auth: undefined },
		});
		expect(reloadedRouter.state.location.pathname).toBe("/login");
		expect(reloadedRouter.state.location.search).toMatchObject({ returnTo: "/about#team" });
		act(() => router.history.back());
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		expect(router.state.location.href).toBe("/about#team");
		act(() => router.history.forward());
		await screen.findByRole("dialog");
		await userEvent.keyboard("{Escape}");
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		expect(router.state.location.href).toBe("/about#team");
		await waitFor(() => expect(document.activeElement).toBe(signIn));
	});

	it("opens the same sign-in dialog from the landing page", async () => {
		renderRouteAtWithRouter("/");
		await screen.findAllByRole("button", { name: "Sign in" }, ROUTE_RENDER_WAIT);
		await userEvent.click(
			within(screen.getByRole("region", { name: /Learn from the work/u })).getByRole("button", {
				name: "Sign in",
			}),
		);
		const dialog = await screen.findByRole("dialog");
		within(dialog).getByRole("button", { name: "Continue with GitHub" });
	});

	it("renders a standalone login for a shared URL without a background page", async () => {
		renderRouteAtWithRouter("/login?returnTo=%2Fabout");
		await screen.findByRole("button", { name: "Continue with GitHub" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("dialog")).toBeNull();
	});

	it("preserves the public destination when sign-in is opened by an unmasked URL", async () => {
		const login = vi.spyOn(authClient, "login").mockReturnValue(undefined);
		const { router } = renderRouteAtWithRouter("/about?login=true&source=invite#team");
		await userEvent.click(
			await screen.findByRole("button", { name: "Continue with GitHub" }, ROUTE_RENDER_WAIT),
		);
		expect(login).toHaveBeenCalledWith("github", "/about?source=invite#team");
		await userEvent.keyboard("{Escape}");
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		expect(router.state.location.href).toBe("/about?source=invite#team");
	});

	it("shows the account deletion notice once, and not again after a reload", async () => {
		recordAccountDeleted(String(currentUser.id));
		renderRouteAtWithRouter("/login");
		const notice = await screen.findByRole("note", undefined, ROUTE_RENDER_WAIT);
		expect(notice.textContent).toContain("Your account is scheduled for deletion");
		expect(notice.textContent).toContain("contact your instance operator");
		// A fresh render models the reload.
		cleanup();
		renderRouteAtWithRouter("/login");
		await screen.findByRole("button", { name: "Continue with GitHub" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("note")).toBeNull();
	});

	it("does not show an account deletion notice recorded too long ago", async () => {
		// oxlint-disable-next-line no-restricted-properties -- The record expires in wall-clock time.
		const recordedAt = Date.now() - ACCOUNT_DELETED_NOTICE_LIFETIME_MS;
		vi.spyOn(Date, "now").mockReturnValueOnce(recordedAt);
		recordAccountDeleted(String(currentUser.id));
		renderRouteAtWithRouter("/login");
		await screen.findByRole("button", { name: "Continue with GitHub" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("note")).toBeNull();
	});

	it("uses the generic error for inherited object-property names", async () => {
		renderRouteAtWithRouter("/login?error=__proto__");
		await screen.findByText("We could not sign you in", undefined, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("alert").textContent).toContain(
			"Try again. If it keeps failing, contact your instance operator.",
		);
	});

	it("retries provider discovery in place", async () => {
		server.use(http.get("*/identity-providers", () => new HttpResponse(null, { status: 503 })));
		renderRouteAtWithRouter("/login");
		await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);
		server.use(
			http.get("*/identity-providers", () =>
				HttpResponse.json([
					{ registrationId: "gitlab", displayName: "GitLab", providerType: "GITLAB" },
				]),
			),
		);
		await userEvent.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("button", { name: "Continue with GitLab" });
	});
});
