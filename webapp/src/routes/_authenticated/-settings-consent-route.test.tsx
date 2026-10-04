import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { getConsentStatusQueryKey } from "@/api/@tanstack/react-query.gen";
import type { ResearchConsent } from "@/api/types.gen";
import { WORDING_VERSION } from "@/components/auth/consent-wording";
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

function showConsent(status: typeof consent, onSave: (body: ResearchConsent) => void) {
	server.use(
		http.get("*/user/consent", () => HttpResponse.json(status)),
		http.put<never, ResearchConsent>("*/user/consent/research", async ({ request }) => {
			const body = await request.json();
			onSave(body);
			return HttpResponse.json({ ...status, participateInResearch: body.granted });
		}),
	);
}

describe("research choice in account settings", () => {
	it("withdraws with one switch and records the organization it named", async () => {
		let saved: ResearchConsent | undefined;
		showConsent(consent, (body) => {
			saved = body;
		});
		renderRouteAtWithRouter("/settings");

		const withdraw = await screen.findByRole(
			"switch",
			{ name: "Allow research use of my data" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(withdraw);

		await waitFor(() =>
			expect(saved).toStrictEqual({
				granted: false,
				noticeVersion: WORDING_VERSION,
				researchOrganization: "AET",
			}),
		);
	});

	it("sends the wording the page showed and tells the reader to reload when the server has moved on", async () => {
		// A tab left open across a release shows the old words. The server refuses a decision filed
		// under any other version, and a retry cannot succeed until the page reloads.
		let saved: ResearchConsent | undefined;
		server.use(
			http.get("*/user/consent", () =>
				HttpResponse.json({
					...consent,
					noticeVersion: "2099-01-01",
					participateInResearch: false,
				}),
			),
			http.put<never, ResearchConsent>("*/user/consent/research", async ({ request }) => {
				saved = await request.json();
				return HttpResponse.json({ status: 409, title: "Conflict" }, { status: 409 });
			}),
		);
		renderRouteAtWithRouter("/settings");

		await userEvent.click(
			await screen.findByRole(
				"switch",
				{ name: "Allow research use of my data" },
				ROUTE_RENDER_WAIT,
			),
		);

		await screen.findByText(/Reload the page, then try again/u);
		expect(saved?.noticeVersion).toBe(WORDING_VERSION);
	});
});
