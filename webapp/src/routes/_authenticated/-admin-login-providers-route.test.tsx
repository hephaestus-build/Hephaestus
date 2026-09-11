import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { LoginProviderView } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

function provider(registrationId: string, displayName: string): LoginProviderView {
	return {
		registrationId,
		displayName,
		type: "oidc",
		baseUrl: `https://${registrationId}.example.test`,
		redirectUri: `https://app.example.test/login/oauth2/code/${registrationId}`,
		scopes: "openid profile",
		enabled: true,
		createdAt: new Date("2026-07-01T00:00:00Z"),
		updatedAt: new Date("2026-07-01T00:00:00Z"),
	};
}

function renderLoginProvidersRoute() {
	renderRouteAt("/admin/login-providers");
}

describe("instance login providers route", () => {
	it("keeps each provider's toggle pending independently when two run at once", async () => {
		let releaseSlowToggle: (() => void) | undefined;
		const slowToggle = new Promise<void>((resolve) => {
			releaseSlowToggle = resolve;
		});
		let slowToggleCalls = 0;
		const providers = [provider("github", "GitHub"), provider("gitlab", "GitLab")];
		server.use(
			http.get("*/admin/login-providers", () => HttpResponse.json(providers)),
			http.patch("*/admin/login-providers/github", async () => {
				slowToggleCalls += 1;
				await slowToggle;
				return HttpResponse.json({ ...providers[0], enabled: false });
			}),
			http.patch("*/admin/login-providers/gitlab", () =>
				HttpResponse.json({ ...providers[1], enabled: false }),
			),
		);

		renderLoginProvidersRoute();

		fireEvent.click(
			await screen.findByRole("switch", { name: "Disable GitHub" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(slowToggleCalls).toBe(1));
		fireEvent.click(screen.getByRole("switch", { name: "Disable GitLab" }));
		await waitFor(() =>
			expect(screen.getByRole("switch", { name: "Disable GitLab" }).getAttribute("aria-busy")).toBe(
				"false",
			),
		);

		expect(screen.getByRole("switch", { name: "Disable GitHub" }).getAttribute("aria-busy")).toBe(
			"true",
		);
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Delete GitHub" }).disabled).toBe(
			true,
		);
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Delete GitLab" }).disabled).toBe(
			false,
		);

		releaseSlowToggle?.();
		await waitFor(() => expect(slowToggleCalls).toBe(1));
	});

	it("asks for a fresh sign-in when a provider change is refused, and recovers its own load failure", async () => {
		let changes = 0;
		server.use(
			http.get("*/admin/login-providers", () =>
				HttpResponse.json([provider("gitlab", "Team GitLab")]),
			),
			http.patch("*/admin/login-providers/gitlab", () => {
				changes += 1;
				return HttpResponse.json(
					{ status: 403, code: "step_up_required", maxAgeSeconds: 300 },
					{ status: 403 },
				);
			}),
			http.get("*/identity-providers", () => HttpResponse.json({}, { status: 503 })),
		);

		renderLoginProvidersRoute();
		fireEvent.click(
			await screen.findByRole("switch", { name: "Disable Team GitLab" }, ROUTE_RENDER_WAIT),
		);

		await screen.findByRole("dialog", { name: "Confirm access" });
		expect((await screen.findByRole("alert")).textContent).toContain(
			"Could not load sign-in options",
		);

		server.use(
			http.get("*/identity-providers", () =>
				HttpResponse.json([
					{ registrationId: "gitlab", providerType: "GITLAB", displayName: "Team GitLab" },
				]),
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Try again" }));
		expect(await screen.findByRole("button", { name: "Continue with Team GitLab" })).not.toBeNull();

		fireEvent.click(screen.getByRole("button", { name: "Close" }));
		await waitFor(() =>
			expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull(),
		);
		// The refused change is abandoned, not replayed, and the row still shows the server's state.
		expect(changes).toBe(1);
		expect(
			screen.getByRole("switch", { name: "Disable Team GitLab" }).getAttribute("aria-checked"),
		).toBe("true");
	});
});
