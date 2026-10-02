import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type PathParams } from "msw";
import { assert, expect, it, vi } from "vitest";

import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import type { ChatMessage } from "@/lib/types";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

function mockCopilot(preference: WorkspaceOnboarding) {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
		),
		// The account carries no flags: the copilot follows the workspace and the member's AI choice.
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () => HttpResponse.json(preference)),
		http.get("*/workspaces/:workspaceSlug/mentor/threads", () => HttpResponse.json([])),
	);
}

it("withholds the floating composer for No AI and after a failed preference refetch", async () => {
	const user = userEvent.setup();
	const preference: WorkspaceOnboarding = { ...workspaceOnboarding(), aiChoice: "NO_AI" };
	mockCopilot(preference);
	const { queryClient } = renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
	const key = getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } });
	const optedIn: WorkspaceOnboarding = {
		...preference,
		aiChoice: "IN_HOUSE_ONLY",
		aiOptions: [
			{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
		],
	};
	server.use(http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(optedIn)));
	await act(async () => {
		queryClient.setQueryData(key, optedIn);
	});
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await screen.findByRole("textbox");
	server.use(
		http.get("*/workspaces/acme/onboarding/me", () => new HttpResponse(null, { status: 503 })),
	);
	await act(async () => {
		await queryClient.invalidateQueries({ queryKey: key });
	});
	await waitFor(() =>
		expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull(),
	);
	expect(screen.queryByRole("textbox")).toBeNull();
});

/** A member who has not chosen, in a workspace whose Heph model is ready. */
function hephReady(): WorkspaceOnboarding {
	return {
		...workspaceOnboarding(),
		aiOptions: [{ choice: "CLOUD", mentorReady: true, practiceReviewsReady: false, models: [] }],
	};
}

it("withholds the floating composer where no Heph model is ready for any choice", async () => {
	mockCopilot({
		...workspaceOnboarding(),
		aiOptions: [{ choice: "CLOUD", mentorReady: false, practiceReviewsReady: true, models: [] }],
	});
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
});

it("starts a separate floating conversation with the new workspace's transport", async () => {
	const user = userEvent.setup();
	mockCopilot(hephReady());
	const requests: { workspace: unknown; id: string }[] = [];
	server.use(
		http.post<PathParams, { id: string }>(
			"*/workspaces/:workspaceSlug/mentor/chat",
			async ({ request, params }) => {
				const body = await request.json();
				requests.push({ workspace: params.workspaceSlug, id: body.id });
				return new HttpResponse(null, { status: 503 });
			},
		),
	);
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await user.type(await screen.findByRole("textbox"), "Acme-only conversation{Enter}");
	await waitFor(() => expect(requests).toHaveLength(1));
	expect(requests[0]?.workspace).toBe("acme");
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/teams",
			params: { workspaceSlug: "other" },
		});
	});
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await screen.findByRole("textbox");
	expect(screen.queryByText("Acme-only conversation")).toBeNull();
	await user.type(screen.getByRole("textbox"), "Other workspace conversation{Enter}");
	await waitFor(() => expect(requests).toHaveLength(2));
	expect(requests[1]?.workspace).toBe("other");
	expect(requests[1]?.id).not.toBe(requests[0]?.id);
});

it("keeps setup free of the floating composer even when AI is available", async () => {
	mockCopilot({
		...workspaceOnboarding(),
		needsSetup: true,
		aiChoice: "IN_HOUSE_ONLY",
		aiOptions: [
			{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
		],
	});
	renderRouteAtWithRouter("/w/acme/onboarding");
	await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
});

it("starts a new panel conversation without the previous thread or its votes, and can reopen the old one", async () => {
	const user = userEvent.setup();
	mockCopilot(hephReady());
	const requests: { id: string; message: ChatMessage }[] = [];
	const votes = new Map<string, Record<string, boolean>>();
	const replies = ["3c634344-99c8-43d5-989a-f1d6913b7537", "96c135a4-74ac-4a1e-b243-c2ad1f150f55"];
	const answers = ["The first answer.", "A separate answer."];
	server.use(
		http.post<PathParams, (typeof requests)[number]>(
			"*/workspaces/acme/mentor/chat",
			async ({ request }) => {
				const body = await request.json();
				requests.push(body);
				const answer = answers[requests.length - 1];
				const chunks = [
					{ type: "start", messageId: replies[requests.length - 1] },
					{ type: "text-start", id: "text" },
					{ type: "text-delta", id: "text", delta: answer },
					{ type: "text-end", id: "text" },
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
		http.post<PathParams, { isUpvoted: boolean }>(
			"*/workspaces/acme/mentor/threads/:threadId/messages/:messageId/vote",
			async ({ params, request }) => {
				const { isUpvoted } = await request.json();
				votes.set(String(params.threadId), { [String(params.messageId)]: isUpvoted });
				return HttpResponse.json({ messageId: params.messageId, isUpvoted });
			},
		),
		http.get("*/workspaces/acme/mentor/threads/:threadId", ({ params }) => {
			const recorded = requests.find((request) => request.id === params.threadId);
			assert.isDefined(recorded);
			const storedVotes = votes.get(recorded.id);
			assert.isDefined(storedVotes);
			return HttpResponse.json({
				id: recorded.id,
				createdAt: "2026-10-02T16:30:00Z",
				messages: [
					recorded.message,
					{
						id: replies[0],
						role: "assistant",
						parts: [{ type: "text", text: "The first answer." }],
					},
				],
				votes: storedVotes,
			});
		}),
	);
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await user.type(
		await screen.findByRole("textbox", { name: "Message" }),
		"First conversation{Enter}",
	);
	await screen.findByText("The first answer.");
	await user.click(await screen.findByRole("button", { name: "Good response" }));
	await waitFor(() =>
		expect(screen.getByRole("button", { name: "Good response" }).getAttribute("aria-pressed")).toBe(
			"true",
		),
	);
	await user.click(screen.getByRole("button", { name: "Start new chat" }));
	await waitFor(() => expect(screen.queryByText("The first answer.")).toBeNull());
	screen.getByRole("textbox", { name: "Message" });
	expect(screen.queryByRole("button", { name: "Good response" })).toBeNull();
	await user.type(screen.getByRole("textbox", { name: "Message" }), "Second conversation{Enter}");
	await screen.findByText("A separate answer.");
	expect(requests).toHaveLength(2);
	expect(requests[1]?.id).not.toBe(requests[0]?.id);
	expect(requests[1]?.message.parts).toStrictEqual([{ type: "text", text: "Second conversation" }]);
	expect(screen.getByRole("button", { name: "Good response" }).getAttribute("aria-pressed")).toBe(
		"false",
	);
	const previous = requests[0];
	assert.isDefined(previous);
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/mentor/$threadId",
			params: { workspaceSlug: "acme", threadId: previous.id },
		});
	});
	await screen.findByText("The first answer.");
	screen.getByText("First conversation");
	expect(screen.queryByText("Second conversation")).toBeNull();
	expect(screen.getByRole("button", { name: "Good response" }).getAttribute("aria-pressed")).toBe(
		"true",
	);
});

it("stops the old response before starting a new panel conversation", async () => {
	const user = userEvent.setup();
	mockCopilot(hephReady());
	const requests: string[] = [];
	let aborted = false;
	server.use(
		http.post<PathParams, { id: string }>(
			"*/workspaces/acme/mentor/chat",
			async ({ request }) => {
				const { id } = await request.json();
				requests.push(id);
				request.signal.addEventListener("abort", () => {
					aborted = true;
				});
				return new HttpResponse(
					new ReadableStream({
						start(controller) {
							controller.enqueue(
								new TextEncoder().encode(
									'data: {"type":"start","messageId":"3c634344-99c8-43d5-989a-f1d6913b7537"}\n\n',
								),
							);
						},
					}),
					{
						headers: { "Content-Type": "text/event-stream", "x-vercel-ai-ui-message-stream": "v1" },
					},
				);
			},
			{ once: true },
		),
		http.post<PathParams, { id: string }>("*/workspaces/acme/mentor/chat", async ({ request }) => {
			const { id } = await request.json();
			requests.push(id);
			return new HttpResponse(null, { status: 503 });
		}),
	);
	renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await user.type(await screen.findByRole("textbox", { name: "Message" }), "Keep answering{Enter}");
	await screen.findByRole("button", { name: "Stop generating" });
	await user.click(screen.getByRole("button", { name: "Start new chat" }));
	await waitFor(() => expect(aborted).toBe(true));
	await waitFor(() => expect(screen.queryByText("Keep answering")).toBeNull());
	await user.type(screen.getByRole("textbox", { name: "Message" }), "Fresh question{Enter}");
	await waitFor(() => expect(requests).toHaveLength(2));
	expect(requests[1]).not.toBe(requests[0]);
});
