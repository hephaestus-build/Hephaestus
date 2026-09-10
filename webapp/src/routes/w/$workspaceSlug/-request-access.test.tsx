import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import type { SubmitWorkspaceAccessRequest } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

function accessPage() {
	server.use(
		http.get("*/workspaces/test/access-entry", () =>
			HttpResponse.json({
				workspaceName: "Test workspace",
				acceptingRequests: true,
				primaryProvider: {
					registrationId: "github",
					displayName: "GitHub",
					providerType: "GITHUB",
					serverUrl: "https://github.com",
				},
			}),
		),
		http.get("*/user/identities", () =>
			HttpResponse.json([{ id: 1, providerType: "GITHUB", serverUrl: "https://github.com" }]),
		),
		http.get("*/workspaces/test/access-requests/me", () => HttpResponse.json([])),
	);
}

describe("workspace access entry", () => {
	it("shows only the workspace's primary provider to an anonymous applicant", async () => {
		accessPage();
		server.use(http.get("*/user", () => new HttpResponse(null, { status: 401 })));
		renderRouteAtWithRouter("/w/test/request-access");
		const signIn = await screen.findByRole(
			"button",
			{ name: "Sign in with GitHub" },
			ROUTE_RENDER_WAIT,
		);
		expect(signIn.textContent).toContain("GitHub");
		expect(screen.queryByRole("button", { name: /GitLab|institutional|TUM/i })).toBeNull();
	});

	it("does not grant membership while preloading or rendering the entry page", async () => {
		accessPage();
		let admissions = 0;
		server.use(
			http.post("*/workspaces/test/access-requests/me/admission", () => {
				admissions += 1;
				return HttpResponse.json({ state: "CHECK_UNAVAILABLE" });
			}),
		);
		renderRouteAtWithRouter("/w/test/request-access");
		const continueButton = await screen.findByRole(
			"button",
			{ name: "Continue" },
			ROUTE_RENDER_WAIT,
		);
		expect(admissions).toBe(0);
		await userEvent.click(continueButton);
		await waitFor(() => expect(admissions).toBe(1));
		expect(
			(await screen.findByText(/membership check is temporarily unavailable/)).textContent,
		).toContain("no access has been granted");
	});

	it("requires linking the correct provider origin into the existing account", async () => {
		accessPage();
		server.use(
			http.get("*/user/identities", () =>
				HttpResponse.json([{ providerType: "GITHUB", serverUrl: "https://github.example.test" }]),
			),
		);
		renderRouteAtWithRouter("/w/test/request-access");
		expect(
			(await screen.findByRole("button", { name: "Connect GitHub" }, ROUTE_RENDER_WAIT))
				.textContent,
		).toContain("Connect");
		expect(screen.queryByRole("button", { name: "Sign in with GitHub" })).toBeNull();
		expect(screen.queryByRole("button", { name: "Continue" })).toBeNull();
	});
	it("allows renewal before expiry and submits the exact reviewed policy and access details", async () => {
		accessPage();
		let submitted: Wire<SubmitWorkspaceAccessRequest> | undefined;
		const nextWeek = new Date();
		nextWeek.setUTCDate(nextWeek.getUTCDate() + 7);
		const expiry = nextWeek.toISOString().slice(0, 10);
		const approved = () => ({
			id: 72,
			accountId: 42,
			displayName: "Applicant",
			status: "SUBMITTED",
			policyVersion: 31,
			submittedAt: new Date().toISOString(),
			requestedDetails: submitted?.details,
		});
		let stored: ReturnType<typeof approved>[] = [];
		server.use(
			http.post("*/workspaces/test/access-requests/me/admission", () =>
				HttpResponse.json({ state: "ACTIVE" }),
			),
			http.get("*/workspaces/test/access-requests/me/form", () =>
				HttpResponse.json({
					policyVersion: 31,
					introductionMarkdown: "Welcome",
					acknowledgementLabel: "I agree to the code of conduct",
					notices: [{ key: "ai", title: "AI policy", markdown: "Read this policy." }],
					requiredLinks: [],
					requestableTeams: [{ id: 5, name: "Platform" }],
					maintainers: [{ accountId: 2, displayName: "Sam Maintainer" }],
					maximumDurationDays: 90,
					verifiedContactAvailable: true,
					accessActive: true,
				}),
			),
			http.get("*/workspaces/test/access-requests/me", () => HttpResponse.json(stored)),
			http.post<never, Wire<SubmitWorkspaceAccessRequest>>(
				"*/workspaces/test/access-requests/me",
				async ({ request }) => {
					submitted = await request.json();
					stored = [approved()];
					return HttpResponse.json(approved());
				},
			),
		);
		const { router } = renderRouteAtWithRouter("/w/test/request-access?renew=true");
		await userEvent.click(
			await screen.findByRole("button", { name: "Continue" }, ROUTE_RENDER_WAIT),
		);
		await userEvent.click(
			await screen.findByRole("checkbox", { name: "I agree to the code of conduct" }),
		);
		await userEvent.click(screen.getByRole("combobox", { name: "Responsible maintainer" }));
		await userEvent.click(await screen.findByRole("option", { name: "Sam Maintainer" }));
		await userEvent.click(screen.getByRole("checkbox", { name: "Platform" }));
		fireEvent.change(screen.getByLabelText("Access until (00:00 UTC)"), {
			target: { value: expiry },
		});
		await userEvent.click(screen.getByRole("checkbox", { name: "I acknowledge AI policy" }));
		await userEvent.click(screen.getByRole("button", { name: "Request access" }));
		await screen.findByRole("heading", { name: "Request #72 · Awaiting review" });
		expect(submitted).toStrictEqual({
			policyVersion: 31,
			introductionAcknowledged: true,
			acknowledgedNoticeKeys: ["ai"],
			details: { maintainerAccountId: 2, teamIds: [5], expiresAt: `${expiry}T00:00:00.000Z` },
		});
		expect(router.state.location.pathname).toBe("/w/test/request-access");
	});
});
