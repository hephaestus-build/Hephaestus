import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

function mockWorkspace(data: WorkspaceOnboarding) {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", ({ params }) =>
			HttpResponse.json({
				...data,
				workspaceName: params.workspaceSlug === "acme" ? "Acme" : "Other workspace",
			}),
		),
	);
}
const firstVisit = {
	...workspaceOnboarding(),
	enabled: true,
	aiChoiceRequired: true,
	needsWelcome: true,
} satisfies WorkspaceOnboarding;

describe("workspace member onboarding route", () => {
	it("saves No AI to only the current workspace before required links are completed", async () => {
		const data = {
			...firstVisit,
			links: [
				{
					connectionId: 9,
					providerType: "SLACK",
					displayName: "Slack",
					required: true,
					available: false,
					linked: false,
				},
			],
		};
		mockWorkspace(data);
		let savedBody: unknown;
		let savedWorkspace: unknown;
		server.use(
			http.put(
				"*/workspaces/:workspaceSlug/onboarding/me/ai-choice",
				async ({ request, params }) => {
					savedWorkspace = params.workspaceSlug;
					savedBody = await request.json();
					return HttpResponse.json({ ...data, aiChoice: "NO_AI" });
				},
			),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: "No AI" }));
		fireEvent.click(screen.getByRole("button", { name: "Save AI preference" }));
		await waitFor(() => expect(savedBody).toStrictEqual({ choice: "NO_AI" }));
		expect(savedWorkspace).toBe("acme");
		await waitFor(() =>
			expect(
				queryClient.getQueryData<WorkspaceOnboarding>(
					getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
				)?.aiChoice,
			).toBe("NO_AI"),
		);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Continue to workspace" }).disabled,
		).toBe(true);
	});
	it("does not send an AI choice when a member chooses Not now", async () => {
		mockWorkspace(firstVisit);
		let dismissals = 0;
		let choices = 0;
		let current: WorkspaceOnboarding = firstVisit;
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
			http.put("*/workspaces/acme/onboarding/me/dismissal", () => {
				dismissals++;
				current = { ...firstVisit, needsWelcome: false };
				return HttpResponse.json(current);
			}),
			http.put("*/workspaces/acme/onboarding/me/ai-choice", () => {
				choices++;
				return HttpResponse.json(firstVisit);
			}),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Not now" }));
		await waitFor(() => expect(dismissals).toBe(1));
		expect(choices).toBe(0);
		await waitFor(() =>
			expect(
				queryClient.getQueryData<WorkspaceOnboarding>(
					getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
				)?.needsWelcome,
			).toBe(false),
		);
	});
	it("discards an unsaved choice when navigating to another workspace", async () => {
		mockWorkspace(firstVisit);
		const { router } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: "No AI" }));
		expect(screen.getByRole("radio", { name: "No AI" }).getAttribute("aria-checked")).toBe("true");
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await screen.findByRole("heading", { name: "Welcome to Other workspace" }, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("radio", { name: "No AI" }).getAttribute("aria-checked")).toBe("false");
	});
	it("keeps the current preference when the server refuses a save", async () => {
		mockWorkspace({ ...firstVisit, aiChoice: "ON_PREMISES" });
		server.use(
			http.put("*/workspaces/acme/onboarding/me/ai-choice", () =>
				HttpResponse.json(
					{ detail: "Only the account owner can choose their AI preference." },
					{ status: 403 },
				),
			),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: "No AI" }));
		fireEvent.click(screen.getByRole("button", { name: "Save AI preference" }));
		await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);
		expect(
			queryClient.getQueryData<WorkspaceOnboarding>(
				getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			)?.aiChoice,
		).toBe("ON_PREMISES");
	});
});
