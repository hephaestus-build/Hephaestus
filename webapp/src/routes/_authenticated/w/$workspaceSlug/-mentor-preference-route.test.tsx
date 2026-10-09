import { act, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";

import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { MentorFake } from "@/test/mentor-server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

it("keeps an existing conversation readable under No AI and restores its composer after opting in", async () => {
	let preference: WorkspaceOnboarding = {
		...workspaceOnboarding(),
		aiChoice: "NO_AI",
		mentorRefusal: "NO_AI",
	};
	const conversation = new MentorFake();
	conversation.add({ kind: "HEPH", text: "Earlier guidance remains readable." });
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(preference)),
		...conversation.handlers(),
	);
	const { queryClient } = renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("Earlier guidance remains readable.", {}, ROUTE_RENDER_WAIT);
	await screen.findByRole("heading", { name: "Heph is off for you" });
	expect(screen.getByRole("link", { name: "Change your AI choice" }).getAttribute("href")).toBe(
		"/w/acme/onboarding?returnTo=/w/acme/mentor",
	);
	expect(screen.queryByRole("textbox")).toBeNull();

	// The server now answers with the new choice too, so a refetch cannot undo the opt-in.
	preference = {
		...workspaceOnboarding(),
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
	await screen.findByRole("textbox", { name: "Message Heph" }, ROUTE_RENDER_WAIT);
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
				mentorRefusal: "UNAVAILABLE",
				aiOptions: [
					{ choice: "CLOUD", mentorReady: false, practiceReviewsReady: true, models: [] },
					{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
				],
			}),
		),
		...new MentorFake().handlers(),
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

it("says Heph is not set up, and offers no choice to change, to a member without a choice whom no model serves", async () => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () =>
			HttpResponse.json({
				...workspaceOnboarding(),
				mentorRefusal: "UNAVAILABLE",
				aiOptions: [
					{ choice: "CLOUD", mentorReady: false, practiceReviewsReady: true, models: [] },
					{ choice: "IN_HOUSE_ONLY", mentorReady: false, practiceReviewsReady: false, models: [] },
				],
			}),
		),
		...new MentorFake().handlers(),
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
		...new MentorFake().handlers(),
	);
	const { router } = renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole("textbox", { name: "Message Heph" }, ROUTE_RENDER_WAIT);
	expect(router.state.location.pathname).toBe("/w/acme/mentor");
});

it("offers Heph to a member who has not chosen, whom the workspace's undeclared model serves", async () => {
	// The server answers such a member from the undeclared slot, which `aiOptions` does not list, and
	// sends no refusal; the page follows the refusal, not the declared options.
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () =>
			HttpResponse.json({
				...workspaceOnboarding(),
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", mentorReady: false, practiceReviewsReady: false, models: [] },
					{ choice: "CLOUD", mentorReady: false, practiceReviewsReady: false, models: [] },
				],
			} satisfies WorkspaceOnboarding),
		),
		...new MentorFake().handlers(),
	);
	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole("textbox", { name: "Message Heph" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByText("Heph is not set up in this workspace yet")).toBeNull();
});

it("lists Heph in the navigation for a member who has not chosen", async () => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/acme/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(workspaceOnboarding())),
	);
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("link", { name: /AI mentor/u }, ROUTE_RENDER_WAIT);
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
				mentorRefusal: "NO_AI",
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
		aiChoice: "CLOUD",
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
				mentorRefusal: "UNAVAILABLE",
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
