import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

describe("personal GitHub access route", () => {
	it("leaves only the current account's target while preserving pending external access", async () => {
		let enrolled = true;
		const offer = () => ({
			targetId: 7,
			workspaceId: 1,
			workspaceSlug: "engineering",
			workspaceName: "Engineering",
			organization: "example-org",
			enrolled,
			managed: true,
			paused: false,
			revocationRequested: !enrolled,
			externalState: "PENDING",
			invitationUrl: "https://github.com/orgs/example-org/invitation",
		});
		server.use(
			http.get("*/user/github-access", () => HttpResponse.json([offer()])),
			http.patch("*/user/github-access/7", async ({ request }) => {
				expect(await request.json()).toStrictEqual({ enrolled: false });
				enrolled = false;
				return HttpResponse.json([offer()]);
			}),
		);
		renderRouteAt("/github-access");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Leave target" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(enrolled).toBe(false));
		await screen.findByText("Removal pending — access remains until GitHub confirms it.");
		expect(
			screen.getByRole<HTMLAnchorElement>("link", { name: "Open GitHub invitation" }).href,
		).toBe("https://github.com/orgs/example-org/invitation");
	});
});
