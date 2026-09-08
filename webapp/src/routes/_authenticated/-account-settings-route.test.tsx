import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

describe("organizational account settings", () => {
	it("offers SCM linking without requesting SCM preferences for an OIDC-only account", async () => {
		let preferenceReads = 0;
		server.use(
			http.get("*/user/identities", () =>
				HttpResponse.json([
					{
						id: 17,
						providerType: "OIDC",
						providerName: "Organization",
						providerId: 9,
						subject: "opaque-subject",
						displayName: "Organization member",
						connectedAt: "2026-09-01T12:00:00Z",
					},
				]),
			),
			http.get("*/user/settings", () => {
				preferenceReads++;
				return HttpResponse.json({ title: "No SCM identity" }, { status: 404 });
			}),
		);
		renderRouteAt("/settings");
		await screen.findByText(/Connect a GitHub or GitLab account below/, {}, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("region", { name: "Account identity" })).not.toBeNull();
		expect(screen.queryByText(/couldn't load your practice feedback preferences/)).toBeNull();
		expect(preferenceReads).toBe(0);
	});
});
