import { focusManager } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";

import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";
import type { ChatThreadDetail, WorkspaceOnboarding } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

it("keeps an existing conversation readable under No AI and restores its composer after opting in", async () => {
	const threadId = "65ee0cb0-99dd-4b0f-86cb-bc8bfb5bbbed";
	let preference: WorkspaceOnboarding = { ...workspaceOnboarding(), aiChoice: "NO_AI" };
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
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
				createdAt: "2026-09-24T09:15:00Z",
				messages: [
					{
						id: "ea28a7c9-b17f-49be-ae98-a083fa99b2b2",
						role: "assistant",
						parts: [{ type: "text", text: "Earlier guidance remains readable." }],
						metadata: { status: "completed" },
						createdAt: "2026-09-24T09:15:04.512Z",
					},
				],
			} satisfies Wire<ChatThreadDetail>),
		),
	);
	const { queryClient } = renderRouteAtWithRouter(`/w/acme/mentor/${threadId}`);
	await screen.findByText("Earlier guidance remains readable.", {}, ROUTE_RENDER_WAIT);
	await screen.findByRole("heading", { name: "Heph is off for you" });
	expect(screen.getByRole("link", { name: "Change your AI choice" }).getAttribute("href")).toBe(
		"/w/acme/onboarding?returnTo=/w/acme/mentor/65ee0cb0-99dd-4b0f-86cb-bc8bfb5bbbed",
	);
	expect(screen.queryByRole("textbox")).toBeNull();
	expect(screen.queryByRole("button", { name: /edit|try again/iu })).toBeNull();

	// The server now answers with the new choice too, so a refetch cannot undo the opt-in.
	preference = {
		...preference,
		aiChoice: "IN_HOUSE_ONLY",
		aiOptions: [
			{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
		],
	};
	await act(async () => {
		queryClient.setQueryData<WorkspaceOnboarding>(
			getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			preference,
		);
	});
	await screen.findByRole("textbox", {}, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("heading", { name: "Heph is off for you" })).toBeNull();
});

it("names the saved choice when no Heph model is set up for it", async () => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
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
					{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
				],
			}),
		),
		http.get("*/workspaces/acme/mentor/threads", () => HttpResponse.json([])),
	);
	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole(
		"heading",
		{ name: "Heph is not set up for your AI choice yet" },
		ROUTE_RENDER_WAIT,
	);
	expect(screen.getByText("Cloud", { selector: "em" }).parentElement?.textContent).toBe(
		"No Heph model is set up for Cloud yet. Nothing switches you elsewhere. Ask a workspace owner, or change your choice.",
	);
	expect(screen.getByRole("link", { name: "Change your AI choice" }).getAttribute("href")).toBe(
		"/w/acme/onboarding?returnTo=/w/acme/mentor",
	);
});

it("says Heph is not set up, and offers no choice to change, where no model is ready for any choice", async () => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
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
					{ choice: "IN_HOUSE_ONLY", mentorReady: false, practiceReviewsReady: false, models: [] },
				],
			}),
		),
		http.get("*/workspaces/acme/mentor/threads", () => HttpResponse.json([])),
	);
	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole(
		"heading",
		{ name: "Heph is not set up in this workspace yet" },
		ROUTE_RENDER_WAIT,
	);
	expect(screen.queryByRole("link", { name: /^(?:Change|Make) your AI choice$/u })).toBeNull();
	expect(screen.queryByRole("textbox")).toBeNull();
});

it("opens Heph for a member whose account carries no feature flags", async () => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
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

it("prepares the member's Heph sandbox when Heph opens, and again when they come back to the tab", async () => {
	const prepared: string[] = [];
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
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
		http.post("*/workspaces/:workspaceSlug/mentor/sandbox", ({ params }) => {
			prepared.push(String(params.workspaceSlug));
			return new HttpResponse(null, { status: 202 });
		}),
	);
	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole("textbox", {}, ROUTE_RENDER_WAIT);
	await waitFor(() => expect(prepared).toStrictEqual(["acme"]), ROUTE_RENDER_WAIT);

	act(() => {
		focusManager.setFocused(false);
		focusManager.setFocused(true);
	});
	await waitFor(() => expect(prepared).toStrictEqual(["acme", "acme"]), ROUTE_RENDER_WAIT);
	focusManager.setFocused(undefined);
});

it("prepares nothing when Heph is off for the member", async () => {
	let prepared = false;
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () =>
			HttpResponse.json({ ...workspaceOnboarding(), aiChoice: "NO_AI" }),
		),
		http.get("*/workspaces/acme/mentor/threads", () => HttpResponse.json([])),
		http.post("*/workspaces/:workspaceSlug/mentor/sandbox", () => {
			prepared = true;
			return new HttpResponse(null, { status: 202 });
		}),
	);
	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole("heading", { name: "Heph is off for you" }, ROUTE_RENDER_WAIT);
	expect(prepared).toBe(false);
});

it("keeps Heph out of the navigation for a reader who is not a member", async () => {
	let membershipAnswered = false;
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
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

it("offers Heph in the navigation where a Heph model is ready, whatever the member chose", async () => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () =>
			HttpResponse.json({
				...workspaceOnboarding(),
				aiChoice: "NO_AI",
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: false, models: [] },
				],
			}),
		),
	);
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("link", { name: /AI mentor/u }, ROUTE_RENDER_WAIT);
});

it("keeps Heph out of the navigation of a workspace where only practice reviews have a model", async () => {
	const ready: WorkspaceOnboarding = {
		...workspaceOnboarding(),
		aiOptions: [{ choice: "CLOUD", mentorReady: true, practiceReviewsReady: true, models: [] }],
	};
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other", { id: 2 })]),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(ready)),
		http.get("*/workspaces/other/onboarding/me", () =>
			HttpResponse.json({
				...ready,
				aiOptions: [
					{ choice: "CLOUD", mentorReady: false, practiceReviewsReady: true, models: [] },
				],
			}),
		),
	);
	const { router, queryClient } = renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("link", { name: /AI mentor/u }, ROUTE_RENDER_WAIT);

	await act(async () => {
		await router.navigate({ to: "/w/$workspaceSlug/teams", params: { workspaceSlug: "other" } });
	});
	await waitFor(
		() =>
			expect(
				queryClient.getQueryState(getMemberOnboardingQueryKey({ path: { workspaceSlug: "other" } }))
					?.status,
			).toBe("success"),
		ROUTE_RENDER_WAIT,
	);
	expect(screen.queryByRole("link", { name: /AI mentor/u })).toBeNull();
});
