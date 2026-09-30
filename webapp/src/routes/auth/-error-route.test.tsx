import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { linkedIdentities } from "@/mocks/fixtures/auth";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { authClient } from "@/runtime/auth/auth-client";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

const ONBOARDING = "/w/intro/onboarding?returnTo=%2Fw%2Fintro%2Factivity&step=accounts";

function stepUp(returnTo?: string): string {
	const search = new URLSearchParams({ code: "step_up_required" });
	if (returnTo !== undefined) {
		search.set("returnTo", returnTo);
	}
	return `/auth/error?${search}`;
}

beforeEach(() => {
	server.use(
		http.get("*/identity-providers", () =>
			HttpResponse.json([
				{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
				{ registrationId: "gitlab", displayName: "GitLab", providerType: "GITLAB" },
				{ registrationId: "slack", displayName: "Slack", providerType: "SLACK" },
			]),
		),
		// Only GitHub is linked: an unlinked sign-in would resolve a different account.
		http.get("*/user/identities", () =>
			HttpResponse.json(linkedIdentities.filter((identity) => identity.providerType === "GITHUB")),
		),
	);
});

const realLocation = window.location;

afterEach(() => {
	Object.defineProperty(window, "location", { configurable: true, value: realLocation });
	vi.restoreAllMocks();
});

/** Replaces `window.location` with one that records every URL assigned to it, as `auth-client.test.ts` does. */
function captureNavigation(): string[] {
	const assigned: string[] = [];
	Object.defineProperty(window, "location", {
		configurable: true,
		value: { assign: (url: string) => assigned.push(url) },
	});
	return assigned;
}

describe("recent sign-in before linking", () => {
	it("signs a still signed-in developer in again with a linked provider, back to where linking was headed", async () => {
		renderRouteAt(stepUp(ONBOARDING));

		await userEvent.click(
			await screen.findByRole("button", { name: "Confirm access" }, ROUTE_RENDER_WAIT),
		);
		const github = await screen.findByRole("button", { name: "Continue with GitHub" });
		expect(screen.queryByRole("button", { name: "Continue with GitLab" })).toBeNull();
		expect(screen.queryByRole("button", { name: "Continue with Slack" })).toBeNull();
		const assigned = captureNavigation();
		await userEvent.click(github);

		expect(assigned).toHaveLength(1);
		const kickoff = new URL(String(assigned[0]));
		expect(kickoff.pathname).toBe("/auth/login");
		expect(kickoff.searchParams.get("provider")).toBe("github");
		expect(kickoff.searchParams.get("returnTo")).toBe(ONBOARDING);
		expect(kickoff.searchParams.has("mode")).toBe(false);
	});

	it("returns a refusal that names no destination to settings", async () => {
		const login = vi.spyOn(authClient, "login").mockReturnValue(undefined);
		renderRouteAt(stepUp());

		await userEvent.click(
			await screen.findByRole("button", { name: "Confirm access" }, ROUTE_RENDER_WAIT),
		);
		await userEvent.click(await screen.findByRole("button", { name: "Continue with GitHub" }));

		expect(login).toHaveBeenCalledExactlyOnceWith("github", "/settings");
	});

	it("offers an ordinary sign-in carrying the destination once the session is gone", async () => {
		server.use(unauthenticatedUser);
		renderRouteAt(stepUp(ONBOARDING));

		const signIn = await screen.findByRole("link", { name: "Back to sign in" }, ROUTE_RENDER_WAIT);

		expect(
			new URL(String(signIn.getAttribute("href")), "http://app.test").searchParams.get("returnTo"),
		).toBe(ONBOARDING);
		expect(screen.queryByRole("button", { name: "Confirm access" })).toBeNull();
	});

	it("keeps other refusals on the plain sign-in link", async () => {
		renderRouteAt("/auth/error?code=link_requires_auth");

		const signIn = await screen.findByRole("link", { name: "Back to sign in" }, ROUTE_RENDER_WAIT);

		expect(signIn.getAttribute("href")).toBe("/login");
	});
});
