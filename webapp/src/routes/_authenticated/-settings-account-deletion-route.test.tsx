import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

import { currentUser } from "@/mocks/fixtures/auth";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, loadRouteCode, renderRouteAt } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/settings", "/login"), ROUTE_RENDER_WAIT.timeout);

const realLocation = window.location;

beforeEach(() => {
	// The page is not workspace-scoped, so the reader belongs to no workspace here.
	server.use(http.get("*/workspaces", () => HttpResponse.json([])));
	// JSDOM has no viewport observer; the landing page uses it only for decorative motion.
	vi.stubGlobal("IntersectionObserver", ObserverStub);
	server.use(
		http.get("*/identity-providers", () =>
			HttpResponse.json([
				{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
			]),
		),
	);
});

afterEach(() => {
	Object.defineProperty(window, "location", { configurable: true, value: realLocation });
	vi.unstubAllGlobals();
	vi.restoreAllMocks();
});

/** Replaces `window.location` with one that records every URL assigned to it, so the tab stays. */
function captureNavigation(): string[] {
	const assigned: string[] = [];
	Object.defineProperty(window, "location", {
		configurable: true,
		value: {
			origin: realLocation.origin,
			pathname: "/settings",
			search: "",
			assign: (url: string) => assigned.push(url),
		},
	});
	return assigned;
}

async function confirmDeletion(): Promise<void> {
	fireEvent.click(await screen.findByRole("button", { name: "Delete account" }, ROUTE_RENDER_WAIT));
	const dialog = await screen.findByRole("alertdialog");
	fireEvent.change(within(dialog).getByLabelText("Confirmation phrase"), {
		target: { value: "delete my account" },
	});
	const confirm = within(dialog).getByRole<HTMLButtonElement>("button", {
		name: "Delete account",
	});
	await waitFor(() => expect(confirm.disabled).toBe(false));
	fireEvent.click(confirm);
}

async function signInPageNotice(): Promise<HTMLElement | null> {
	cleanup();
	server.use(unauthenticatedUser);
	renderRouteAt("/login");
	await screen.findByRole("button", { name: "Continue with GitHub" }, ROUTE_RENDER_WAIT);
	return screen.queryByRole("note");
}

describe("account deletion on the settings page", () => {
	it("shows the accepted notice without another logout request", async () => {
		const logout = vi.fn(() => HttpResponse.error());
		server.use(http.post("*/auth/logout", logout));
		renderRouteAt("/settings");
		const assigned = captureNavigation();

		await confirmDeletion();

		await waitFor(() => expect(assigned).toStrictEqual(["/login"]));
		const notice = await signInPageNotice();
		expect(notice?.textContent).toContain("Your deletion request was accepted");
		expect(logout).not.toHaveBeenCalled();
	});

	it("does not show the notice to another person who signs in and out in the same tab", async () => {
		renderRouteAt("/settings");
		const assigned = captureNavigation();
		await confirmDeletion();
		await waitFor(() => expect(assigned).toStrictEqual(["/login"]));

		// The tab stays, and another person signs in through the sign-in dialog.
		cleanup();
		server.use(http.get("*/user", () => HttpResponse.json({ ...currentUser, id: 7 })));
		renderRouteAt("/settings");
		await screen.findByRole("heading", { name: "Danger zone" }, ROUTE_RENDER_WAIT);

		// They sign out, and later the sign-in page opens in this tab.
		await expect(signInPageNotice()).resolves.toBeNull();
	});

	it("records no notice when the server refuses the deletion", async () => {
		server.use(http.delete("*/user", () => new HttpResponse(null, { status: 500 })));
		renderRouteAt("/settings");
		const assigned = captureNavigation();

		await confirmDeletion();

		await screen.findByText("We could not delete your account. Try again.");
		expect(assigned).toStrictEqual([]);
		await expect(signInPageNotice()).resolves.toBeNull();
	});
});
