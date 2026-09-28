import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";

import type { ChatMessageVote, ChatMessageVoteRequest, ChatThreadDetail } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

const threadId = "65ee0cb0-99dd-4b0f-86cb-bc8bfb5bbbed";
const replyId = "ea28a7c9-b17f-49be-ae98-a083fa99b2b2";

it("keeps the thumb a member chose on a reply when they reopen the conversation", async () => {
	// The server's side of it: a vote is stored, and the thread read carries the stored votes.
	const stored: Record<string, boolean> = {};
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
		http.get("*/workspaces/acme/mentor/threads", () =>
			HttpResponse.json([{ id: threadId, title: "Earlier conversation" }]),
		),
		http.get("*/workspaces/acme/mentor/threads/:threadId", () =>
			HttpResponse.json({
				id: threadId,
				createdAt: "2026-09-24T09:15:00Z",
				messages: [
					{
						id: replyId,
						role: "assistant",
						parts: [{ type: "text", text: "Link the issue in the description.", state: "done" }],
						metadata: { status: "completed" },
						createdAt: "2026-09-24T09:15:04.512Z",
					},
				],
				votes: stored,
			} satisfies Wire<ChatThreadDetail>),
		),
		http.post<{ messageId: string }, ChatMessageVoteRequest>(
			"*/workspaces/acme/mentor/threads/:threadId/messages/:messageId/vote",
			async ({ params, request }) => {
				const body = await request.json();
				const vote = {
					messageId: params.messageId,
					isUpvoted: body.isUpvoted,
					updatedAt: "2026-09-24T09:16:00.021Z",
				} satisfies Wire<ChatMessageVote>;
				stored[vote.messageId] = vote.isUpvoted;
				return HttpResponse.json(vote);
			},
		),
	);

	renderRouteAt(`/w/acme/mentor/${threadId}`);
	await userEvent.click(
		await screen.findByRole("button", { name: "Bad response" }, ROUTE_RENDER_WAIT),
	);
	await waitFor(() => expect(stored).toStrictEqual({ [replyId]: false }));

	cleanup();
	renderRouteAt(`/w/acme/mentor/${threadId}`);

	await screen.findByText("Link the issue in the description.", {}, ROUTE_RENDER_WAIT);
	await waitFor(() =>
		expect(screen.getByRole("button", { name: "Bad response" }).getAttribute("aria-pressed")).toBe(
			"true",
		),
	);
	expect(screen.getByRole("button", { name: "Good response" }).getAttribute("aria-pressed")).toBe(
		"false",
	);
});
