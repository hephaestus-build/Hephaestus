import { screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { getConsentStatusQueryKey } from "@/api/@tanstack/react-query.gen";
import { WORDING_VERSION } from "@/components/auth/ConsentPage";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

const consent = {
	completed: true,
	noticeVersion: WORDING_VERSION,
	participateInResearch: true,
	researchOrganization: "AET",
};

describe("account settings and a notice that falls due again", () => {
	it("returns to setup when the research question is asked afresh", async () => {
		// Renaming the research organisation leaves its question unanswered, which is what the server
		// then reports; the page has to learn that from a refetch rather than from a navigation.
		let current: typeof consent = consent;
		server.use(http.get("*/user/consent", () => HttpResponse.json(current)));
		const { router, queryClient } = renderRouteAtWithRouter("/settings");
		await screen.findByRole("heading", { name: "User settings" }, ROUTE_RENDER_WAIT);

		current = { ...consent, completed: false, researchOrganization: "Another lab" };
		await queryClient.invalidateQueries({ queryKey: getConsentStatusQueryKey({}) });

		// The parent guard only runs on navigation, so without the route's own effect the reader is
		// left on a page whose controls have gone and whose writes the server has started refusing.
		await waitFor(() => expect(router.state.location.pathname).toBe("/consent"));
		expect(router.state.location.search).toMatchObject({ returnTo: "/settings" });
	});
});
