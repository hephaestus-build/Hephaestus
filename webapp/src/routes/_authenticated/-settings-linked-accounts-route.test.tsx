import {
	type BoundFunctions,
	cleanup,
	fireEvent,
	type queries,
	screen,
	waitFor,
	within,
} from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, assert, describe, expect, it, vi } from "vitest";

import type { IdentityProviderView, IdentityView, LinkedProvider } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { captureNavigation, restoreNavigation } from "@/test/navigation";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

afterEach(restoreNavigation);

/** A connected account as the server keeps it: the identity row and the origin it is linked to. */
interface Connected {
	identity: Wire<IdentityView>;
	origin: LinkedProvider;
}

function connected(id: number, displayName: string, origin: LinkedProvider): Connected {
	return {
		identity: { id, providerType: origin.type ?? "", subject: `subject-${id}`, displayName },
		origin,
	};
}

const github = connected(1, "The Octocat", {
	type: "GITHUB",
	serverUrl: "https://api.github.com",
});
const publicGitLab = connected(2, "Tux on GitLab.com", {
	type: "GITLAB",
	serverUrl: "https://gitlab.com",
});
const lrzGitLab = connected(3, "Tux at LRZ", {
	type: "GITLAB",
	serverUrl: "https://gitlab.lrz.de",
});
const acmeOutline = connected(4, "Octo at ACME", {
	type: "OUTLINE",
	serverUrl: "https://outline.acme.test",
});
const researchOutline = connected(5, "Octo in Research", {
	type: "OUTLINE",
	serverUrl: "https://docs.research.test",
});

/** What the instance offers to connect, with the canonical origin discovery reports. */
const providers: IdentityProviderView[] = [
	{
		registrationId: "github",
		displayName: "GitHub",
		providerType: "GITHUB",
		baseUrl: "https://github.com",
	},
	{
		registrationId: "gitlab",
		displayName: "GitLab",
		providerType: "GITLAB",
		baseUrl: "https://gitlab.com",
	},
	{
		registrationId: "gitlab-lrz",
		displayName: "LRZ GitLab",
		providerType: "GITLAB",
		baseUrl: "https://gitlab.lrz.de",
	},
	{
		registrationId: "outline-acme",
		displayName: "ACME Outline",
		providerType: "OUTLINE",
		baseUrl: "https://outline.acme.test",
	},
	{
		registrationId: "outline-research",
		displayName: "Research Outline",
		providerType: "OUTLINE",
		baseUrl: "https://docs.research.test",
	},
	{ registrationId: "dev", displayName: "Developer sign-in", providerType: "DEV" },
];

/**
 * The account behind `/user` and `/user/identities`, which every read takes fresh from here. A
 * disconnect removes the identity and the origin it was linked to.
 */
function serveAccount(initial: Connected[]) {
	const account = { connected: [...initial], disconnected: [] as number[] };
	server.use(
		http.get("*/user", () =>
			HttpResponse.json({
				...currentUser,
				linkedProviders: account.connected.map(({ origin }) => origin),
			}),
		),
		http.get("*/user/identities", () =>
			HttpResponse.json(account.connected.map(({ identity }) => identity)),
		),
		http.get("*/identity-providers", () => HttpResponse.json(providers)),
		http.delete("*/user/identities/:id", ({ params }) => {
			const id = Number(params.id);
			account.disconnected.push(id);
			account.connected = account.connected.filter(({ identity }) => identity.id !== id);
			return new HttpResponse(null, { status: 204 });
		}),
	);
	return account;
}

async function connectedAccounts() {
	const region = await screen.findByRole(
		"region",
		{ name: "Connected accounts" },
		ROUTE_RENDER_WAIT,
	);
	await within(region).findAllByRole("button", { name: /^Disconnect /u });
	return within(region);
}

function connectAction(region: BoundFunctions<typeof queries>, label: string) {
	return region.queryAllByRole("button", { name: `Connect ${label}` });
}

describe("connected accounts on the settings page", () => {
	it.each(["/settings", "/settings?chromeWorkspace=beta#connected-accounts"])(
		"keeps the current settings destination when connecting an account from %s",
		async (returnTo) => {
			serveAccount([github, publicGitLab]);
			renderRouteAt(returnTo);
			const region = await connectedAccounts();
			const assigned = captureNavigation();

			fireEvent.click(region.getByRole("button", { name: "Connect LRZ GitLab" }));

			await waitFor(() => expect(assigned).toHaveLength(1));
			const [target] = assigned;
			assert(target !== undefined);
			const authorization = new URL(target);
			expect(authorization.origin + authorization.pathname).toBe(
				"http://localhost:8080/auth/login",
			);
			expect(authorization.searchParams.get("provider")).toBe("gitlab-lrz");
			expect(authorization.searchParams.get("mode")).toBe("link");
			expect(authorization.searchParams.get("returnTo")).toBe(returnTo);
		},
	);

	it("offers another GitLab instance while GitLab.com is connected, until that instance is connected too", async () => {
		const account = serveAccount([github, publicGitLab]);
		renderRouteAt("/settings");
		const region = await connectedAccounts();

		expect(connectAction(region, "LRZ GitLab")).toHaveLength(1);
		expect(connectAction(region, "GitLab")).toHaveLength(0);
		expect(connectAction(region, "GitHub")).toHaveLength(0);
		expect(connectAction(region, "Developer sign-in")).toHaveLength(0);

		// Connecting goes through the provider's sign-in and back, so the page reads the account anew.
		account.connected.push(lrzGitLab);
		cleanup();
		renderRouteAt("/settings");
		const after = await connectedAccounts();
		await after.findByText("Tux at LRZ");
		expect(connectAction(after, "LRZ GitLab")).toHaveLength(0);
		expect(connectAction(after, "GitLab")).toHaveLength(0);
	});

	it("keeps a second Outline offered while the first is connected, and offers the first again once it is disconnected", async () => {
		const account = serveAccount([github, publicGitLab, acmeOutline]);
		renderRouteAt("/settings");
		const region = await connectedAccounts();

		expect(connectAction(region, "Research Outline")).toHaveLength(1);
		expect(connectAction(region, "ACME Outline")).toHaveLength(0);

		account.connected.push(researchOutline);
		cleanup();
		renderRouteAt("/settings");
		const both = await connectedAccounts();
		await both.findByText("Octo in Research");
		expect(connectAction(both, "Research Outline")).toHaveLength(0);
		expect(connectAction(both, "ACME Outline")).toHaveLength(0);

		fireEvent.click(both.getByRole("button", { name: "Disconnect Octo at ACME" }));
		fireEvent.click(await screen.findByRole("button", { name: "Disconnect" }));

		await waitFor(() => expect(connectAction(both, "ACME Outline")).toHaveLength(1));
		expect(account.disconnected).toStrictEqual([4]);
		expect(both.queryByText("Octo at ACME")).toBeNull();
		expect(connectAction(both, "Research Outline")).toHaveLength(0);
	});

	it("keeps the only sign-in account beside a link-only one, and lets the link-only one go", async () => {
		const account = serveAccount([github, acmeOutline]);
		renderRouteAt("/settings");
		const region = await connectedAccounts();

		// GitLab is offered to connect, which would let GitHub go; Outline would not.
		region.getByText(
			"This is your only sign-in method. To remove it, first connect another sign-in account below.",
		);
		expect(region.queryByRole("button", { name: "Disconnect The Octocat" })).toBeNull();

		fireEvent.click(region.getByRole("button", { name: "Disconnect Octo at ACME" }));
		fireEvent.click(await screen.findByRole("button", { name: "Disconnect" }));
		await waitFor(() => expect(account.disconnected).toStrictEqual([4]));
		await waitFor(() => expect(region.queryByText("Octo at ACME")).toBeNull());
		expect(region.queryByRole("button", { name: "Disconnect The Octocat" })).toBeNull();
	});

	it("lets either of two sign-in accounts go, and keeps the one left", async () => {
		const account = serveAccount([github, publicGitLab, acmeOutline]);
		renderRouteAt("/settings");
		const region = await connectedAccounts();
		expect(region.queryByText(/only sign-in method/u)).toBeNull();

		fireEvent.click(region.getByRole("button", { name: "Disconnect Tux on GitLab.com" }));
		fireEvent.click(await screen.findByRole("button", { name: "Disconnect" }));
		await waitFor(() => expect(account.disconnected).toStrictEqual([2]));
		await region.findByText(/only sign-in method/u);
		expect(region.queryByRole("button", { name: "Disconnect The Octocat" })).toBeNull();
		region.getByRole("button", { name: "Disconnect Octo at ACME" });
	});

	it("treats a connected account with no recorded origin as unknown, not as every instance", async () => {
		serveAccount([
			connected(1, "The Octocat", { type: "GITHUB" }),
			connected(2, "Tux somewhere", { type: "GITLAB" }),
			connected(3, "Octo in some Outline", { type: "OUTLINE" }),
		]);
		renderRouteAt("/settings");
		const region = await connectedAccounts();

		expect(connectAction(region, "GitLab")).toHaveLength(1);
		expect(connectAction(region, "LRZ GitLab")).toHaveLength(1);
		expect(connectAction(region, "GitHub")).toHaveLength(0);
		expect(region.queryByText("You’ve connected all available providers.")).toBeNull();

		// The Outline identity may belong to either deployment, so both stay offered and neither is
		// said to be unconnected.
		region.getByText("Octo in some Outline");
		expect(connectAction(region, "ACME Outline")).toHaveLength(1);
		expect(connectAction(region, "Research Outline")).toHaveLength(1);
		expect(region.queryAllByText(/is not connected$/u)).toHaveLength(0);
	});
});
