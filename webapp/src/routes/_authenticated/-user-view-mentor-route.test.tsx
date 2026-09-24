import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, it } from "vitest";

import { server } from "@/mocks/server";
import { clearUserView } from "@/runtime/user-view/session";
import { renderRouteAt } from "@/test/router-harness";

afterEach(clearUserView);

it("opens saved conversations without using the administrator's mentor setting", async () => {
	sessionStorage.setItem(
		"hephaestus.user-view",
		JSON.stringify({
			operatorAccountId: 42,
			workspaceSlug: "engineering",
			userId: 11,
			login: "alex",
			name: "Alex",
			hasAccount: false,
			reason: "Check conversation",
		}),
	);
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([
				{
					id: 1,
					workspaceSlug: "engineering",
					displayName: "Engineering",
					status: "ACTIVE",
					mentorEnabled: true,
					practicesEnabled: true,
					leaderboardEnabled: true,
					progressionEnabled: false,
					leaguesEnabled: false,
				},
			]),
		),
		http.get("*/user/features", () => HttpResponse.json({ MENTOR_ACCESS: false })),
		http.get("*/workspaces/:workspaceSlug/mentor/threads", () => HttpResponse.json([])),
	);

	renderRouteAt("/w/engineering/mentor");
	await screen.findByText("Choose a saved conversation from the sidebar.");
});
