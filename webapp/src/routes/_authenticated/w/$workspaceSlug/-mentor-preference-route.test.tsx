import { act, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";

import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

it("keeps an existing conversation readable under No AI and restores its composer after opting in", async () => {
	const threadId = "65ee0cb0-99dd-4b0f-86cb-bc8bfb5bbbed";
	const preference = { ...workspaceOnboarding(), aiChoice: "NO_AI" as const };
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { mentorEnabled: true })]),
		),
		http.get("*/user/features", () => HttpResponse.json({ MENTOR_ACCESS: true })),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(preference)),
		http.get("*/workspaces/acme/mentor/threads", () =>
			HttpResponse.json([{ id: threadId, title: "Earlier conversation" }]),
		),
		http.get("*/workspaces/acme/mentor/threads/:threadId", () =>
			HttpResponse.json({
				id: threadId,
				messages: [
					{
						id: "ea28a7c9-b17f-49be-ae98-a083fa99b2b2",
						role: "assistant",
						parts: [{ type: "text", text: "Earlier guidance remains readable." }],
					},
				],
			}),
		),
	);
	const { queryClient } = renderRouteAtWithRouter(`/w/acme/mentor/${threadId}`);
	await screen.findByText("Earlier guidance remains readable.", {}, ROUTE_RENDER_WAIT);
	await screen.findByRole("heading", { name: "Heph is off for you in this workspace" });
	expect(screen.queryByRole("textbox")).toBeNull();
	expect(screen.queryByRole("button", { name: /edit|try again/i })).toBeNull();

	await act(async () => {
		queryClient.setQueryData<WorkspaceOnboarding>(
			getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			{
				...preference,
				aiChoice: "ON_PREMISES",
				aiOptions: [{ choice: "ON_PREMISES", mentorReady: true, practiceReviewsReady: true }],
			},
		);
	});
	await screen.findByRole("textbox", {}, ROUTE_RENDER_WAIT);
	expect(
		screen.queryByRole("heading", { name: "Heph is off for you in this workspace" }),
	).toBeNull();
});
