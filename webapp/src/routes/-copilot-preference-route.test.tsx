import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type PathParams } from "msw";
import { expect, it, vi } from "vitest";

import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

function mockCopilot(preference: WorkspaceOnboarding) {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([
				workspaceListItem("acme", { mentorEnabled: true }),
				workspaceListItem("other", { mentorEnabled: true }),
			]),
		),
		http.get("*/user/features", () => HttpResponse.json({ MENTOR_ACCESS: true })),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () => HttpResponse.json(preference)),
		http.get("*/workspaces/:workspaceSlug/mentor/threads", () => HttpResponse.json([])),
	);
}

it("withholds the floating composer for No AI and after a failed preference refetch", async () => {
	const user = userEvent.setup();
	const preference: WorkspaceOnboarding = { ...workspaceOnboarding(), aiChoice: "NO_AI" };
	mockCopilot(preference);
	const { queryClient } = renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
	const key = getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } });
	const optedIn: WorkspaceOnboarding = {
		...preference,
		aiChoice: "IN_HOUSE_ONLY",
		aiOptions: [{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true }],
	};
	server.use(http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(optedIn)));
	await act(async () => {
		queryClient.setQueryData(key, optedIn);
	});
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await screen.findByRole("textbox");
	server.use(
		http.get("*/workspaces/acme/onboarding/me", () => new HttpResponse(null, { status: 503 })),
	);
	await act(async () => {
		await queryClient.invalidateQueries({ queryKey: key });
	});
	await waitFor(() =>
		expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull(),
	);
	expect(screen.queryByRole("textbox")).toBeNull();
});

it("starts a separate floating conversation with the new workspace's transport", async () => {
	const user = userEvent.setup();
	mockCopilot(workspaceOnboarding());
	const requests: { workspace: unknown; id: string }[] = [];
	server.use(
		http.post<PathParams, { id: string }>(
			"*/workspaces/:workspaceSlug/mentor/chat",
			async ({ request, params }) => {
				const body = await request.json();
				requests.push({ workspace: params.workspaceSlug, id: body.id });
				return new HttpResponse(null, { status: 503 });
			},
		),
	);
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await user.type(await screen.findByRole("textbox"), "Acme-only conversation{Enter}");
	await waitFor(() => expect(requests).toHaveLength(1));
	expect(requests[0]?.workspace).toBe("acme");
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/teams",
			params: { workspaceSlug: "other" },
		});
	});
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await screen.findByRole("textbox");
	expect(screen.queryByText("Acme-only conversation")).toBeNull();
	await user.type(screen.getByRole("textbox"), "Other workspace conversation{Enter}");
	await waitFor(() => expect(requests).toHaveLength(2));
	expect(requests[1]?.workspace).toBe("other");
	expect(requests[1]?.id).not.toBe(requests[0]?.id);
});

it("keeps setup free of the floating composer even when AI is available", async () => {
	mockCopilot({
		...workspaceOnboarding(),
		needsWelcome: true,
		aiChoice: "IN_HOUSE_ONLY",
		aiOptions: [{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true }],
	});
	renderRouteAtWithRouter("/w/acme/onboarding");
	await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
});
