import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { problemType } from "@/lib/problem-detail";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

/** A browser that can hold passkeys; no test here reaches its prompt. */
class BrowserCredential {
	readonly payload = {};
	static parseCreationOptionsFromJSON(options: unknown) {
		return options;
	}
	static parseRequestOptionsFromJSON(options: unknown) {
		return options;
	}
	toJSON() {
		return this.payload;
	}
}

// Only this one global is replaced and put back: the test setup owns others, such as the event
// source, that a blanket unstub would delete for every later case.
beforeEach(() => {
	const original = Object.getOwnPropertyDescriptor(globalThis, "PublicKeyCredential");
	Object.defineProperty(globalThis, "PublicKeyCredential", {
		configurable: true,
		value: BrowserCredential,
	});
	return () => {
		if (original === undefined) {
			Reflect.deleteProperty(globalThis, "PublicKeyCredential");
		} else {
			Object.defineProperty(globalThis, "PublicKeyCredential", original);
		}
	};
});

/** The server's refusal of a change whose confirmation is no longer recent. */
const confirmationRequired = () =>
	HttpResponse.json(
		{
			status: 403,
			title: "Confirm access with a passkey",
			detail:
				"This action requires passkey verification. Open your passkey settings to confirm access.",
			type: problemType("passkey-required"),
		},
		{ status: 403 },
	);

/**
 * Two passkeys, confirmed recently or not. Starting an enrollment or a removal finds the confirmation
 * aged out, as the server refuses it, and every later read says so.
 */
function servePasskeys(verified: boolean) {
	const account = { verified, enrollmentStarts: 0, registrations: 0, removals: 0 };
	server.use(
		http.get("*/user/passkeys", () =>
			HttpResponse.json({
				assuranceEnforced: true,
				protectionEnabled: true,
				recoveryRequired: false,
				instanceAdminRequired: false,
				workspaceAdminRequired: false,
				verified: account.verified,
				credentials: [
					{ id: "one", label: "Laptop" },
					{ id: "two", label: "Phone" },
				],
			}),
		),
		http.post("*/user/passkeys/registration-options", () => {
			account.enrollmentStarts += 1;
			account.verified = false;
			return confirmationRequired();
		}),
		http.post("*/user/passkeys", () => {
			account.registrations += 1;
			return new HttpResponse(null, { status: 204 });
		}),
		http.delete("*/user/passkeys/:credentialId", () => {
			account.removals += 1;
			account.verified = false;
			return confirmationRequired();
		}),
	);
	return account;
}

/**
 * One passkey, confirmed recently, with personal protection off. The removal either succeeds, after
 * which every read shows no passkey, or is refused because protection was turned on elsewhere.
 */
function serveLastPasskey(answer: "removed" | "refused") {
	const account = { removed: false, protectionEnabled: false, removals: 0 };
	server.use(
		http.get("*/user/passkeys", () =>
			HttpResponse.json({
				assuranceEnforced: true,
				protectionEnabled: account.protectionEnabled,
				recoveryRequired: false,
				instanceAdminRequired: true,
				workspaceAdminRequired: false,
				verified: !account.removed,
				credentials: account.removed ? [] : [{ id: "one", label: "Phone" }],
			}),
		),
		http.delete("*/user/passkeys/:credentialId", () => {
			account.removals += 1;
			if (answer === "refused") {
				account.protectionEnabled = true;
				return HttpResponse.json(
					{
						status: 409,
						title: "Conflict",
						detail: "Turn off personal admin protection before you remove your last passkey.",
					},
					{ status: 409 },
				);
			}
			account.removed = true;
			return new HttpResponse(null, { status: 200 });
		}),
	);
	return account;
}

async function passkeys() {
	const region = await screen.findByRole("region", { name: "Passkeys" }, ROUTE_RENDER_WAIT);
	await within(region).findByText("Phone");
	return within(region);
}

describe("passkeys on the settings page", () => {
	it("lists the passkeys among the page's sections, leading to the address sign-in returns to", async () => {
		servePasskeys(false);
		renderRouteAt("/settings");
		await passkeys();
		const nav = within(screen.getByRole("navigation", { name: "Settings sections" }));
		const link = nav.getByRole("link", { name: "Passkeys" });
		// The server sends a recent sign-in back to `/settings#passkeys`.
		expect(link.getAttribute("href")).toBe("#passkeys");
		const target = document.getElementById("passkeys");
		expect(target?.contains(screen.getByRole("region", { name: "Passkeys" }))).toBe(true);
	});

	it("asks for a confirmation before a second passkey is added, and starts no enrollment", async () => {
		const account = servePasskeys(false);
		renderRouteAt("/settings");
		const region = await passkeys();

		const register = region.getByRole("button", { name: "Register passkey" });
		expect(register.getAttribute("aria-disabled")).toBe("true");
		await userEvent.click(register);
		expect(account.enrollmentStarts).toBe(0);
		region.getByRole("button", { name: "Verify passkey" });
	});

	it("reads the status again when adding a passkey finds the confirmation expired, and asks once", async () => {
		const account = servePasskeys(true);
		renderRouteAt("/settings");
		const region = await passkeys();
		expect(region.queryByRole("button", { name: "Verify passkey" })).toBeNull();

		await userEvent.click(region.getByRole("button", { name: "Register passkey" }));

		await region.findByText(
			"Your passkey confirmation has expired. Verify your passkey again, then retry.",
			{},
			ROUTE_RENDER_WAIT,
		);
		await region.findByRole("button", { name: "Verify passkey" });
		await waitFor(() =>
			expect(
				region.getByRole("button", { name: "Register passkey" }).getAttribute("aria-disabled"),
			).toBe("true"),
		);
		// Refused before the browser prompt: nothing is created, sent or asked for again.
		expect(account.enrollmentStarts).toBe(1);
		expect(account.registrations).toBe(0);
	});

	it("says in place that a confirmation expired, and locks the changes until the next one", async () => {
		const account = servePasskeys(true);
		renderRouteAt("/settings");
		const region = await passkeys();
		expect(region.queryByRole("button", { name: "Verify passkey" })).toBeNull();

		await userEvent.click(region.getByRole("button", { name: "Remove Phone" }));
		await userEvent.click(await screen.findByRole("button", { name: "Remove passkey" }));

		await region.findByText(
			"Your passkey confirmation has expired. Verify your passkey again, then retry.",
			{},
			ROUTE_RENDER_WAIT,
		);
		expect(account.removals).toBe(1);
		expect(region.queryByText(/Open your passkey settings/u)).toBeNull();
		await region.findByRole("button", { name: "Verify passkey" });
		await waitFor(() =>
			expect(
				region.getByRole("button", { name: "Remove Phone" }).getAttribute("aria-disabled"),
			).toBe("true"),
		);
		region.getByText("Phone");
	});

	it("removes the last passkey once, then reads that none is left", async () => {
		const account = serveLastPasskey("removed");
		renderRouteAt("/settings");
		const region = await passkeys();

		await userEvent.click(region.getByRole("button", { name: "Remove Phone" }));
		await userEvent.click(await screen.findByRole("button", { name: "Remove last passkey" }));

		await region.findByText(
			"No passkeys yet. Register a passkey to protect admin access.",
			{},
			ROUTE_RENDER_WAIT,
		);
		expect(account.removals).toBe(1);
		expect(region.queryByText("Phone")).toBeNull();
	});

	it("keeps the last passkey listed when the server refuses its removal, and does not send it again", async () => {
		const account = serveLastPasskey("refused");
		renderRouteAt("/settings");
		const region = await passkeys();

		await userEvent.click(region.getByRole("button", { name: "Remove Phone" }));
		await userEvent.click(await screen.findByRole("button", { name: "Remove last passkey" }));

		await region.findByText(
			"Turn off personal admin protection before you remove your last passkey.",
			{ selector: "[role='alert']" },
			ROUTE_RENDER_WAIT,
		);
		region.getByText("Phone");
		expect(region.queryByText(/No passkeys yet/u)).toBeNull();
		expect(account.removals).toBe(1);
	});
});
