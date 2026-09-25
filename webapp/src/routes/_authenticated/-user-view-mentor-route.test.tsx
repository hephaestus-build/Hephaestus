import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from "vitest";

import { getThreadOptions } from "@/api/@tanstack/react-query.gen";
import { client } from "@/api/client.gen";
import { server } from "@/mocks/server";
import { applyUserViewHeaders, clearUserView } from "@/runtime/user-view/session";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";

beforeAll(() => client.interceptors.request.use(applyUserViewHeaders));
beforeEach(() => {
	storeUserView();
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
	);
});
afterEach(clearUserView);
afterAll(() => client.interceptors.request.eject(applyUserViewHeaders));

it("opens saved conversations without using the administrator's mentor setting", async () => {
	let viewedUser: string | null = null;
	server.use(
		http.get("*/workspaces/:workspaceSlug/mentor/threads", ({ request }) => {
			viewedUser = request.headers.get("X-User-View-User");
			return HttpResponse.json([]);
		}),
	);

	renderRouteAt("/w/engineering/mentor");
	await screen.findByRole("heading", { name: "No conversation selected" }, ROUTE_RENDER_WAIT);
	await waitFor(() => expect(viewedUser).toBe("11"));
});

const stepUpRequired = () =>
	HttpResponse.json({ status: 403, code: "step_up_required", maxAgeSeconds: 300 }, { status: 403 });

it("asks for a recent sign-in when a viewed read is refused for it", async () => {
	server.use(http.get("*/workspaces/:workspaceSlug/mentor/threads", stepUpRequired));

	renderRouteAt("/w/engineering/mentor");
	await screen.findByRole("dialog", { name: "Confirm access" }, ROUTE_RENDER_WAIT);
});

it("asks again when a later read is refused after the first prompt was dismissed", async () => {
	server.use(
		http.get("*/workspaces/:workspaceSlug/mentor/threads", stepUpRequired),
		http.get("*/workspaces/:workspaceSlug/mentor/threads/:threadId", stepUpRequired),
	);
	const queryClient = renderRouteAt("/w/engineering/mentor");
	await screen.findByRole("dialog", { name: "Confirm access" }, ROUTE_RENDER_WAIT);
	await userEvent.keyboard("{Escape}");
	await waitFor(() => expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull());

	// The dismissed refusal stays cached on the thread list while a newer read is refused.
	await act(async () => {
		await expect(
			queryClient.query(
				getThreadOptions({ path: { workspaceSlug: "engineering", threadId: "thread-1" } }),
			),
		).rejects.toMatchObject({ code: "step_up_required" });
	});
	await screen.findByRole("dialog", { name: "Confirm access" });
});
