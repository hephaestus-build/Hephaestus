import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";

import type { ChatMessage, ChatThreadDetail } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

const threadId = "18a4740d-c558-4d49-8fe6-118c76bd723b";
const promptId = "95d58e22-2c05-4e43-9e91-242f28b43089";
const interruptedId = "b9f319e9-e4ea-4ef1-8541-79256cab9120";
const answerId = "c3f0a1d2-5e6b-4c7d-8e9f-0a1b2c3d4e5f";

const prompt = {
	id: promptId,
	role: "user",
	parts: [{ type: "text", text: "Plan issue 12" }],
	metadata: { status: "completed" },
	createdAt: "2026-09-30T00:06:21.622Z",
} satisfies Wire<ChatMessage>;

function detail(reply: Wire<ChatMessage>): Wire<ChatThreadDetail> {
	return { id: threadId, createdAt: prompt.createdAt, messages: [prompt, reply], votes: {} };
}

it("tries a reply saved as interrupted again after the conversation is reopened", async () => {
	let stored = detail({
		id: interruptedId,
		role: "assistant",
		parts: [],
		metadata: { status: "interrupted" },
		createdAt: "2026-09-30T00:06:21.644Z",
	});
	const posted: { id: string; message: { id: string }; trigger: string; messageId?: string }[] = [];
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
			HttpResponse.json([{ id: threadId, title: "Plan issue 12" }]),
		),
		http.get("*/workspaces/acme/mentor/threads/:threadId", () => HttpResponse.json(stored)),
		http.post<Record<string, never>, (typeof posted)[number]>(
			"*/workspaces/acme/mentor/chat",
			async ({ request }) => {
				posted.push(await request.json());
				stored = detail({
					id: answerId,
					role: "assistant",
					parts: [{ type: "text", text: "Here is a plan.", state: "done" }],
					metadata: { status: "completed" },
					createdAt: "2026-09-30T00:12:00.000Z",
				});
				const chunks = [
					{ type: "start", messageId: answerId },
					{ type: "text-start", id: "t" },
					{ type: "text-delta", id: "t", delta: "Here is a plan." },
					{ type: "text-end", id: "t" },
					{ type: "finish" },
				];
				return new HttpResponse(
					[...chunks.map((chunk) => `data: ${JSON.stringify(chunk)}\n\n`), "data: [DONE]\n\n"].join(
						"",
					),
					{
						headers: { "Content-Type": "text/event-stream", "x-vercel-ai-ui-message-stream": "v1" },
					},
				);
			},
		),
	);

	renderRouteAt(`/w/acme/mentor/${threadId}`);
	await userEvent.click(
		await screen.findByRole("button", { name: "Try again" }, ROUTE_RENDER_WAIT),
	);

	await screen.findByText("Here is a plan.");
	expect(posted).toHaveLength(1);
	expect(posted[0]).toMatchObject({
		id: threadId,
		message: { id: promptId },
		trigger: "regenerate-message",
		messageId: interruptedId,
	});
	await waitFor(() => expect(screen.queryByRole("button", { name: "Try again" })).toBeNull());
});
