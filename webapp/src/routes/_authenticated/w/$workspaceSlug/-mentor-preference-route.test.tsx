import { act, screen, waitFor } from "@testing-library/react";
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
		http.get("*/user/features", () => HttpResponse.json({})),
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
	await screen.findByRole("heading", { name: "Heph is off for you" });
	expect(screen.getByRole("link", { name: "Change your AI choice" }).getAttribute("href")).toBe(
		"/w/acme/onboarding?returnTo=%2Fw%2Facme%2Fmentor%2F65ee0cb0-99dd-4b0f-86cb-bc8bfb5bbbed",
	);
	expect(screen.queryByRole("textbox")).toBeNull();
	expect(screen.queryByRole("button", { name: /edit|try again/iu })).toBeNull();

	await act(async () => {
		queryClient.setQueryData<WorkspaceOnboarding>(
			getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			{
				...preference,
				aiChoice: "IN_HOUSE_ONLY",
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
				],
			},
		);
	});
	await screen.findByRole("textbox", {}, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("heading", { name: "Heph is off for you" })).toBeNull();
});

it("names the saved choice when no Heph model is within it", async () => {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { mentorEnabled: true })]),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () =>
			HttpResponse.json({
				...workspaceOnboarding(),
				aiChoice: "CLOUD",
				aiOptions: [
					{ choice: "CLOUD", mentorReady: false, practiceReviewsReady: true, models: [] },
				],
			}),
		),
		http.get("*/workspaces/acme/mentor/threads", () => HttpResponse.json([])),
	);
	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole(
		"heading",
		{ name: "Heph isn't set up for your AI choice yet" },
		ROUTE_RENDER_WAIT,
	);
	expect(screen.getByText("Cloud", { selector: "em" }).parentElement?.textContent).toBe(
		"No Heph model is within Cloud yet. Nothing switches you elsewhere. Ask a workspace owner, or change your choice.",
	);
	expect(screen.getByRole("link", { name: "Change your AI choice" }).getAttribute("href")).toBe(
		"/w/acme/onboarding?returnTo=%2Fw%2Facme%2Fmentor",
	);
});

it("opens Heph for a member whose account carries no feature flags", async () => {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { mentorEnabled: true })]),
		),
		// Heph follows the workspace and the member's AI choice, not an account flag.
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () =>
			HttpResponse.json({
				...workspaceOnboarding(),
				aiChoice: "IN_HOUSE_ONLY",
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
				],
			}),
		),
		http.get("*/workspaces/acme/mentor/threads", () => HttpResponse.json([])),
	);
	const { router } = renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole("textbox", {}, ROUTE_RENDER_WAIT);
	// A new chat opens under its own thread id; what matters is that the member stayed in Heph.
	expect(router.state.location.pathname.startsWith("/w/acme/mentor")).toBe(true);
});

it("says Heph is off when a link opens it in a workspace that has it off", async () => {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { mentorEnabled: false })]),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(workspaceOnboarding())),
	);
	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole("heading", { name: "Heph is off in this workspace" }, ROUTE_RENDER_WAIT);
	expect(screen.getByRole("link", { name: "Go to workspace home" }).getAttribute("href")).toBe(
		"/w/acme",
	);
	expect(screen.queryByRole("textbox")).toBeNull();
});

it("keeps Heph out of the navigation for a reader who is not a member", async () => {
	let membershipAnswered = false;
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { mentorEnabled: true })]),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		// The server answers `members/me` only for a member of the workspace.
		http.get("*/workspaces/acme/members/me", () => {
			membershipAnswered = true;
			return HttpResponse.json({ status: 400 }, { status: 400 });
		}),
		http.get("*/workspaces/acme/onboarding/me", () =>
			HttpResponse.json({ status: 403 }, { status: 403 }),
		),
	);
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	await waitFor(() => expect(membershipAnswered).toBe(true), ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("link", { name: /AI mentor/u })).toBeNull();
});

it("offers Heph in the navigation to a member of a workspace that has it on", async () => {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { mentorEnabled: true })]),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(workspaceOnboarding())),
	);
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("link", { name: /AI mentor/u }, ROUTE_RENDER_WAIT);
});
