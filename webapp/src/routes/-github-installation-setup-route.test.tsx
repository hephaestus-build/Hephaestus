import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import type { GitHubInstallationClaim } from "@/api/types.gen";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

const APPROVAL_NOTICE =
	"An instance administrator must approve this installation before Hephaestus can use it.";

/** Serves the claim with the outcome the test chose and records each body that the page sends. */
function serveClaim(answer: GitHubInstallationClaim) {
	const bodies: unknown[] = [];
	server.use(
		http.post("*/github-installations/:installationId/claim", async ({ request, params }) => {
			bodies.push({ installationId: params.installationId, body: await request.json() });
			return HttpResponse.json(answer);
		}),
	);
	return bodies;
}

describe("GitHub App setup page", () => {
	it("sends GitHub's code only after the installer continues, then says who decides next", async () => {
		const bodies = serveClaim({ outcome: "AWAITING_APPROVAL" });
		renderRouteAt("/github/installations/4242/setup?code=one-time");

		const proceed = await screen.findByRole("button", { name: "Continue" }, ROUTE_RENDER_WAIT);
		expect(bodies).toHaveLength(0);
		await userEvent.setup().click(proceed);

		await screen.findByText(APPROVAL_NOTICE);
		expect(bodies).toStrictEqual([{ installationId: "4242", body: { code: "one-time" } }]);
	});

	it("shows the approval notice to a signed-out person and sends nothing", async () => {
		const bodies = serveClaim({ outcome: "AWAITING_APPROVAL" });
		server.use(
			unauthenticatedUser,
			http.get("*/workspaces/providers", () => HttpResponse.json({ creationPolicy: "ADMIN_ONLY" })),
		);
		renderRouteAt("/github/installations/4242/setup?code=one-time");

		await screen.findByText(APPROVAL_NOTICE, undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: "Continue" })).toBeNull();
		expect(bodies).toHaveLength(0);
	});
});
