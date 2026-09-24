import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { expect, it } from "vitest";

import type { PracticeReleaseProposal } from "@/api/types.gen";
import { mockPullRequestBinding, mockPullRequestPolicy } from "@/mocks/fixtures/practice";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

const base = {
	name: "Explain the change",
	bindings: [mockPullRequestBinding],
	criteria: "Earlier criteria",
	automatedReviewPolicy: mockPullRequestPolicy,
	deliveryBehavior: { summaryOnly: false },
};

const proposal: PracticeReleaseProposal = {
	slug: "explain-the-change",
	base,
	current: { ...base, criteria: "Our criteria" },
	offered: { ...base, criteria: "New catalog criteria" },
	baseSource: "EXACT_ADOPTION",
	offeredDigest: "digest",
	etag: "reviewed-offer",
	currentRevision: 1,
	fields: [{ field: "CRITERIA", offeredChanged: true, conflict: true }],
};

it("accepts a workspace update with explicit field choice and the reviewed version", async () => {
	let receivedTag: string | null = null;
	let receivedBody: unknown;
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([
				{
					id: 1,
					workspaceSlug: "acme",
					displayName: "Acme",
					providerType: "GITHUB",
					status: "ACTIVE",
					practicesEnabled: false,
					mentorEnabled: false,
					leaderboardEnabled: false,
					progressionEnabled: false,
					leaguesEnabled: false,
				},
			]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/practices/releases", () => HttpResponse.json([proposal])),
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
		http.put("*/workspaces/:workspaceSlug/practices/releases/:slug", async ({ request }) => {
			receivedTag = request.headers.get("if-match");
			receivedBody = await request.json();
			server.use(
				http.get("*/workspaces/:workspaceSlug/practices/releases", () => HttpResponse.json([])),
			);
			return HttpResponse.json({});
		}),
	);
	renderRouteAt("/w/acme/admin/practices/releases");

	const choices = await screen.findByRole(
		"radiogroup",
		{ name: "Use a version for Review criteria" },
		ROUTE_RENDER_WAIT,
	);
	expect(screen.getByRole("button", { name: "Accept selected fields" })).toHaveProperty(
		"disabled",
		true,
	);
	fireEvent.click(within(choices).getByRole("radio", { name: "Offered" }));
	fireEvent.click(screen.getByRole("button", { name: "Accept selected fields" }));

	await waitFor(() => expect(receivedTag).toBe('"reviewed-offer"'));
	expect(receivedBody).toStrictEqual({ choices: { CRITERIA: "OFFERED" } });
	await screen.findByText("No updates to review", undefined, ROUTE_RENDER_WAIT);
});
