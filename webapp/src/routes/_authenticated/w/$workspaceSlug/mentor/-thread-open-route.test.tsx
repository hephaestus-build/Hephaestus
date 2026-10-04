import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, it, vi } from "vitest";

import type { ChatThreadDetail } from "@/api/types.gen";
import { mentorThreadOptions } from "@/hooks/use-mentor-chat";
import type { Wire } from "@/lib/dates";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

const threadId = "18a4740d-c558-4d49-8fe6-118c76bd723b";
const replyId = "c3f0a1d2-5e6b-4c7d-8e9f-0a1b2c3d4e5f";

const stored = {
	id: threadId,
	createdAt: "2026-09-30T00:06:21.622Z",
	messages: [
		{
			id: "95d58e22-2c05-4e43-9e91-242f28b43089",
			role: "user",
			parts: [{ type: "text", text: "Plan issue 12" }],
			metadata: { status: "completed" },
			createdAt: "2026-09-30T00:06:21.622Z",
		},
		{
			id: replyId,
			role: "assistant",
			parts: [{ type: "text", text: "Here is a plan.", state: "done" }],
			metadata: { status: "completed" },
			createdAt: "2026-09-30T00:06:22.000Z",
		},
	],
	votes: {},
} satisfies Wire<ChatThreadDetail>;

// A message id that is not a UUID fails `parseThreadMessages`, as any malformed transcript does.
const unreadable = {
	...stored,
	messages: [{ ...stored.messages[0], id: "msg-1" }],
} satisfies Wire<ChatThreadDetail>;

beforeEach(() => {
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
	);
});

it.each([
	["missing", () => new HttpResponse(null, { status: 404 })],
	["unreadable", () => HttpResponse.json(unreadable)],
])("says so in place when the conversation is %s", async (_name, respond) => {
	server.use(http.get("*/workspaces/acme/mentor/threads/:threadId", respond));

	renderRouteAt(`/w/acme/mentor/${threadId}`);

	// The only thing on the page, so it is the page's heading.
	await screen.findByRole(
		"heading",
		{ level: 1, name: "We could not open this conversation" },
		ROUTE_RENDER_WAIT,
	);
	expect(screen.queryByRole("textbox", { name: "Message" })).toBeNull();
});

it("names the page for a screen reader when the conversation opens", async () => {
	server.use(
		http.get("*/workspaces/acme/mentor/threads/:threadId", () => HttpResponse.json(stored)),
	);

	renderRouteAt(`/w/acme/mentor/${threadId}`);

	await screen.findByRole(
		"heading",
		{ level: 1, name: "Conversation with Heph" },
		ROUTE_RENDER_WAIT,
	);
	screen.getByRole("textbox", { name: "Message" });
});

it("opens the conversation when trying again succeeds", async () => {
	server.use(
		http.get(
			"*/workspaces/acme/mentor/threads/:threadId",
			() => new HttpResponse(null, { status: 503 }),
			{ once: true },
		),
		http.get("*/workspaces/acme/mentor/threads/:threadId", () => HttpResponse.json(stored)),
	);

	renderRouteAt(`/w/acme/mentor/${threadId}`);
	await userEvent.click(await screen.findByRole("button", { name: "Retry" }, ROUTE_RENDER_WAIT));

	await screen.findByText("Here is a plan.");
});

it.each([
	["fails", () => new HttpResponse(null, { status: 503 })],
	["comes back unreadable", () => HttpResponse.json(unreadable)],
])("keeps an open conversation when a later read of it %s", async (_name, respond) => {
	const laterRead = vi.fn(respond);
	server.use(
		http.get("*/workspaces/acme/mentor/threads/:threadId", () => HttpResponse.json(stored), {
			once: true,
		}),
		http.get("*/workspaces/acme/mentor/threads/:threadId", laterRead),
		http.post("*/workspaces/acme/mentor/threads/:threadId/messages/:messageId/vote", () =>
			HttpResponse.json({ messageId: replyId, isUpvoted: true }),
		),
	);

	const queryClient = renderRouteAt(`/w/acme/mentor/${threadId}`);
	// A vote reads the conversation again for the stored votes.
	await userEvent.click(
		await screen.findByRole("button", { name: "Good response" }, ROUTE_RENDER_WAIT),
	);

	await waitFor(() => expect(laterRead).toHaveBeenCalledOnce());
	await waitFor(() =>
		expect(
			queryClient.getQueryState(mentorThreadOptions("acme", threadId).queryKey)?.fetchStatus,
		).toBe("idle"),
	);
	screen.getByText("Here is a plan.");
	expect(screen.queryByText("We could not open this conversation")).toBeNull();
});
