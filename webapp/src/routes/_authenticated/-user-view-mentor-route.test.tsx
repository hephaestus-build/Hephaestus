import { act, cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from "vitest";

import { getMentorConversationOptions } from "@/api/@tanstack/react-query.gen";
import { client } from "@/api/client.gen";
import { server } from "@/mocks/server";
import { applyUserViewHeaders, clearUserView } from "@/runtime/user-view/session";
import { FakeEventSource } from "@/test/fake-event-source";
import { MentorFake } from "@/test/mentor-server";
import { ROUTE_RENDER_WAIT, renderRouteAt, testQueryClient } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";

let queryClient: ReturnType<typeof testQueryClient>;

beforeAll(() => client.interceptors.request.use(applyUserViewHeaders));
beforeEach(() => {
	queryClient = testQueryClient();
	storeUserView();
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([
				{
					id: 1,
					workspaceSlug: "engineering",
					displayName: "Engineering",
					status: "ACTIVE",
					practicesEnabled: true,
				},
			]),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		// The saved AI choice belongs to the signed-in account; the server refuses it in a view.
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () =>
			HttpResponse.json({ status: 403 }, { status: 403 }),
		),
	);
});
afterEach(async () => {
	server.events.removeAllListeners();
	cleanup();
	await queryClient.cancelQueries();
	queryClient.clear();
	clearUserView();
});
afterAll(() => client.interceptors.request.eject(applyUserViewHeaders));

it("opens the viewed member's conversation read-only, without a live stream", async () => {
	const conversation = new MentorFake("engineering");
	const question = conversation.add({ kind: "USER", text: "What should I improve?" });
	conversation.add({ kind: "HEPH", text: "Earlier guidance remains readable." });
	conversation.add({ kind: "USER", text: "Which pull request?", parentEntryId: question.id });
	const requests: Request[] = [];
	server.events.on("request:start", ({ request }) => {
		requests.push(request);
	});
	server.use(...conversation.handlers());

	renderRouteAt(`/w/engineering/mentor?thread=${question.id}`, queryClient);
	await screen.findByText("Earlier guidance remains readable.", {}, ROUTE_RENDER_WAIT);
	await screen.findByText("Which pull request?");
	const viewedUsers = requests
		.filter(({ url }) => new URL(url).pathname.includes("/mentor/"))
		.map(({ headers }) => headers.get("X-User-View-Account"));
	expect(new Set(viewedUsers)).toStrictEqual(new Set(["11"]));
	expect(screen.queryByRole("textbox")).toBeNull();
	expect(screen.getAllByRole("button", { name: /^Copy message \(/u }).length).toBeGreaterThan(0);
	expect(screen.queryByRole("button", { name: /^Helpful/u })).toBeNull();
	expect(screen.queryByRole("button", { name: /^Delete message/u })).toBeNull();
	expect(screen.queryByRole("heading", { name: "Heph is off for you" })).toBeNull();
	expect(FakeEventSource.instances).toHaveLength(0);
});

it("says that a viewed member with no conversation has no messages, and whose they would be", async () => {
	server.use(...new MentorFake("engineering").handlers());

	renderRouteAt("/w/engineering/mentor", queryClient);
	await screen.findByText("No messages yet", {}, ROUTE_RENDER_WAIT);
	screen.getByText("Questions Alex asks Heph, here or in Slack, show up with Heph’s answers.");
	// The greeting invites the reader to ask, which a view cannot.
	expect(screen.queryByRole("textbox")).toBeNull();
});

const stepUpRequired = () =>
	HttpResponse.json({ status: 403, code: "step_up_required", maxAgeSeconds: 300 }, { status: 403 });

it("asks for a recent sign-in when a viewed read is refused for it", async () => {
	server.use(http.get("*/workspaces/:workspaceSlug/mentor/conversation", stepUpRequired));

	renderRouteAt("/w/engineering/mentor", queryClient);
	await screen.findByRole("dialog", { name: "Confirm access" }, ROUTE_RENDER_WAIT);
});

it("asks again when a later read is refused after the first prompt was dismissed", async () => {
	server.use(
		http.get("*/workspaces/:workspaceSlug/mentor/conversation", stepUpRequired),
		http.get("*/workspaces/:workspaceSlug/mentor/entries", stepUpRequired),
	);
	renderRouteAt("/w/engineering/mentor", queryClient);
	await screen.findByRole("dialog", { name: "Confirm access" }, ROUTE_RENDER_WAIT);
	await userEvent.keyboard("{Escape}");
	await waitFor(() => expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull());

	// The dismissed refusal stays cached on the conversation while a newer read is refused.
	await act(async () => {
		await expect(
			queryClient.query({
				...getMentorConversationOptions({ path: { workspaceSlug: "engineering" } }),
				staleTime: 0,
			}),
		).rejects.toMatchObject({ code: "step_up_required" });
	});
	await screen.findByRole("dialog", { name: "Confirm access" });
});
