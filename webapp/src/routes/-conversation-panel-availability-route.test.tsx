import { act, cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { assert, expect, it, vi } from "vitest";

import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { detailReview } from "@/stories/practice-detail-story-mock-data";
import { ConversationFake } from "@/test/conversation-server";
import { FakeEventSource } from "@/test/fake-event-source";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

function mockConversationPanel(preference: WorkspaceOnboarding) {
	const conversations = {
		acme: new ConversationFake("acme"),
		other: new ConversationFake("other"),
	};
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
		),
		// The account carries no flags: the Hephaestus panel follows the workspace and the member's AI choice.
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/:workspaceSlug/members/me", () => HttpResponse.json(ownMembership())),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () => HttpResponse.json(preference)),
		...conversations.acme.handlers(),
		...conversations.other.handlers(),
	);
	return conversations;
}

it("withholds the floating composer for No AI and after a failed preference refetch", async () => {
	const user = userEvent.setup();
	const preference: WorkspaceOnboarding = {
		...workspaceOnboarding(),
		aiChoice: "NO_AI",
		mentorRefusal: "NO_AI",
	};
	mockConversationPanel(preference);
	const { queryClient } = renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Ask Hephaestus" })).toBeNull();
	const key = getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } });
	const optedIn: WorkspaceOnboarding = {
		...workspaceOnboarding(),
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
		await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
	);
	await screen.findByRole("textbox");
	server.use(
		http.get("*/workspaces/acme/onboarding/me", () => new HttpResponse(null, { status: 503 })),
	);
	await act(async () => {
		await queryClient.invalidateQueries({ queryKey: key });
	});
	await waitFor(() => expect(screen.queryByRole("button", { name: "Ask Hephaestus" })).toBeNull());
	expect(screen.queryByRole("textbox")).toBeNull();
});

/** A member who has not chosen, in a workspace whose conversation model is ready. */
function conversationReady(): WorkspaceOnboarding {
	return {
		...workspaceOnboarding(),
		aiOptions: [{ choice: "CLOUD", mentorReady: true, practiceReviewsReady: false, models: [] }],
	};
}

it("withholds the floating composer where no conversation model is ready for any choice", async () => {
	mockConversationPanel({
		...workspaceOnboarding(),
		aiChoice: "CLOUD",
		mentorRefusal: "UNAVAILABLE",
		aiOptions: [{ choice: "CLOUD", mentorReady: false, practiceReviewsReady: true, models: [] }],
	});
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Ask Hephaestus" })).toBeNull();
});

it("keeps each workspace's conversation in its own panel", async () => {
	const user = userEvent.setup();
	const { acme, other } = mockConversationPanel(conversationReady());
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
	);
	await user.type(
		await screen.findByRole("textbox", { name: "Message Hephaestus" }),
		"Acme-only conversation{Enter}",
	);
	await waitFor(() => expect(acme.submissions).toHaveLength(1));
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/teams",
			params: { workspaceSlug: "other" },
		});
	});
	await user.click(
		await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
	);
	await user.type(
		await screen.findByRole("textbox", { name: "Message Hephaestus" }),
		"Other workspace conversation{Enter}",
	);
	await waitFor(() => expect(other.submissions).toHaveLength(1));
	expect(screen.queryByText("Acme-only conversation")).toBeNull();
	expect(acme.submissions).toHaveLength(1);
});

it("leaves an unsent draft behind in its own workspace", async () => {
	const user = userEvent.setup();
	const { acme, other } = mockConversationPanel(conversationReady());
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
	);
	await user.type(
		await screen.findByRole("textbox", { name: "Message Hephaestus" }),
		"Private draft from acme",
	);
	await act(async () => {
		await router.navigate({ to: "/w/$workspaceSlug/teams", params: { workspaceSlug: "other" } });
	});
	await user.click(
		await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
	);
	const composer = await screen.findByRole(
		"textbox",
		{ name: "Message Hephaestus" },
		ROUTE_RENDER_WAIT,
	);
	expect(composer).toHaveProperty("value", "");
	await user.type(composer, "{Enter}");
	expect(other.submissions).toStrictEqual([]);
	expect(acme.submissions).toStrictEqual([]);
});

it("keeps setup free of the floating composer even when AI is available", async () => {
	mockConversationPanel({
		...workspaceOnboarding(),
		needsSetup: true,
		aiChoice: "IN_HOUSE_ONLY",
		aiOptions: [
			{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
		],
	});
	renderRouteAtWithRouter("/w/acme/onboarding");
	await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Ask Hephaestus" })).toBeNull();
});

it("shares the conversation with the conversation page and sends the page it was asked from", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	acme.add({ kind: "HEPH", text: "Earlier in this conversation." });
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
	);
	await screen.findByText("Earlier in this conversation.");
	screen.getByText("From Teams");
	await user.type(
		screen.getByRole("textbox", { name: "Message Hephaestus" }),
		"Who is on my team?{Enter}",
	);
	await waitFor(() =>
		expect(acme.submissions.map(({ text, pageContext }) => ({ text, pageContext }))).toStrictEqual([
			{ text: "Who is on my team?", pageContext: "Teams" },
		]),
	);

	await user.click(screen.getByRole("button", { name: "Do not send this page with your message" }));
	await user.type(
		screen.getByRole("textbox", { name: "Message Hephaestus" }),
		"Without the page{Enter}",
	);
	await waitFor(() => expect(acme.submissions).toHaveLength(2));
	expect(acme.submissions[1]?.pageContext).toBeUndefined();

	await user.click(screen.getByRole("button", { name: "Open full page" }));
	await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/conversation"));
	await screen.findByText("Who is on my team?");
	// The page is the conversation, so the launcher stays away from it.
	expect(screen.queryByRole("button", { name: "Ask Hephaestus" })).toBeNull();
});

it("opens a thread as a view over the conversation, and carries it to the conversation page", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	const parent = acme.add({ kind: "HEPH", text: "Three pull requests left the reason out." });
	acme.add({ kind: "USER", text: "Which ones?", parentEntryId: parent.id });
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
	);
	const summary = await screen.findByRole("button", { name: /^1 reply/u });
	await user.click(summary);
	await screen.findByText("Which ones?");
	screen.getByRole("textbox", { name: "Reply in thread" });
	// Opened to read, so focus goes to the title.
	await waitFor(() => expect(document.activeElement?.textContent).toBe("Thread"));

	await user.click(screen.getByRole("button", { name: "Back to the conversation" }));
	await waitFor(() => expect(screen.queryByText("Which ones?")).toBeNull());
	// The conversation stayed mounted underneath, so focus returns to the control that opened the thread.
	expect(document.activeElement).toBe(summary);

	await user.click(await screen.findByRole("button", { name: /^1 reply/u }));
	await screen.findByText("Which ones?");
	await user.click(screen.getByRole("button", { name: "Open full page" }));
	await waitFor(() => expect(router.state.location.search).toMatchObject({ thread: parent.id }));
});

it("goes back from a thread to the conversation on Escape, and closes the panel on the next", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	const parent = acme.add({ kind: "HEPH", text: "Three pull requests left the reason out." });
	acme.add({ kind: "USER", text: "Which ones?", parentEntryId: parent.id });
	renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
	);
	const summary = await screen.findByRole("button", { name: /^1 reply/u });
	await user.click(summary);
	await screen.findByText("Which ones?");

	await user.keyboard("{Escape}");
	await waitFor(() => expect(screen.queryByText("Which ones?")).toBeNull());
	// One level only: the panel stays open on the conversation, with focus where the thread opened.
	screen.getByRole("dialog");
	screen.getByText("Three pull requests left the reason out.");
	expect(document.activeElement).toBe(summary);

	await user.keyboard("{Escape}");
	await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	screen.getByRole("button", { name: "Ask Hephaestus" });
});

/** The live stream the closed panel's launcher keeps open, after its first snapshot. */
async function openStream(fake: ConversationFake): Promise<FakeEventSource> {
	await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT);
	const stream = await waitFor(() => {
		const latest = FakeEventSource.latest();
		assert.isDefined(latest);
		return latest;
	});
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
	});
	return stream;
}

/** Hephaestus's answer while it streams: each new word is its own element, so match the whole paragraph. */
function streamedText(text: string) {
	return (_content: string, element: Element | null) =>
		element?.tagName === "P" && element.textContent === text;
}

it("keeps Hephaestus's answer streaming when the Hephaestus page opens mid-turn, and never asks again", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	const panelStream = await openStream(acme);
	await user.click(screen.getByRole("button", { name: "Ask Hephaestus" }));
	// The composer comes with the conversation's first page, which the panel reads as it opens.
	await user.type(
		await screen.findByRole("textbox", { name: "Message Hephaestus" }),
		"How did my week go?{Enter}",
	);
	await waitFor(() => expect(acme.turns).toHaveLength(1));
	const [turn] = acme.turns;
	assert.isDefined(turn);
	Object.assign(turn, {
		status: "RUNNING",
		attempt: 1,
		partial: { activity: "WRITING", stage: "Thinking…", text: "You opened two" },
	});
	act(() => panelStream.emit("turn", turn));
	await screen.findByText(streamedText("You opened two"));

	await user.click(screen.getByRole("button", { name: "Open full page" }));
	await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/conversation"));
	// The page shows the answer where the panel left it, before its own stream says anything.
	await screen.findByText(streamedText("You opened two"), {}, ROUTE_RENDER_WAIT);

	const pageStream = await waitFor(() => {
		const latest = FakeEventSource.latest();
		assert.isDefined(latest);
		expect(latest).not.toBe(panelStream);
		return latest;
	});
	act(() => {
		pageStream.open();
		pageStream.emit("snapshot", acme.conversation());
		pageStream.emit("turn", {
			...turn,
			partial: { activity: "WRITING", stage: "Thinking…", text: "You opened two pull requests" },
		});
	});
	await screen.findByText(streamedText("You opened two pull requests"));
	expect(acme.submissions.map(({ text }) => text)).toStrictEqual(["How did my week go?"]);
});

it("marks the launcher when an answer lands while the panel is closed, and clears it when the panel opens", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	const question = acme.add({ kind: "USER", text: "How did my week go?" });
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT);
	const stream = await waitFor(() => {
		const latest = FakeEventSource.latest();
		assert.isDefined(latest);
		return latest;
	});
	act(() => {
		stream.open();
		stream.emit("snapshot", acme.conversation());
	});

	const answer = acme.add({
		kind: "HEPH",
		text: "You opened two pull requests.",
		turnId: question.turnId,
	});
	act(() => {
		stream.emit("entry", answer);
	});
	await user.click(await screen.findByRole("button", { name: "Ask Hephaestus, new answer" }));
	await screen.findByText("You opened two pull requests.");
	await user.keyboard("{Escape}");
	await screen.findByRole("button", { name: "Ask Hephaestus" });
	expect(screen.queryByRole("button", { name: /new answer/u })).toBeNull();
});

it("leaves the launcher unmarked for a question the developer asked in Slack", async () => {
	const { acme } = mockConversationPanel(conversationReady());
	renderRouteAtWithRouter("/w/acme/teams");
	const stream = await openStream(acme);
	const question = acme.add({ kind: "USER", surface: "SLACK", text: "Asked from Slack" });
	await act(async () => {
		stream.emit("entry", question);
	});
	expect(screen.queryByRole("button", { name: /new answer/u })).toBeNull();
	screen.getByRole("button", { name: "Ask Hephaestus" });
});

it("marks the launcher when Hephaestus answers in a thread", async () => {
	const { acme } = mockConversationPanel(conversationReady());
	const parent = acme.add({ kind: "USER", text: "How did my week go?" });
	renderRouteAtWithRouter("/w/acme/teams");
	const stream = await openStream(acme);
	const reply = acme.add({
		kind: "HEPH",
		text: "Share blockers earlier.",
		parentEntryId: parent.id,
	});
	act(() => {
		stream.emit("entry", reply);
	});
	await screen.findByRole("button", { name: "Ask Hephaestus, new answer" });
});

it("marks the launcher on reconnect for a whole Slack turn that ran while the stream was down, and opening clears it", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	acme.lastReadAt = acme.add({ kind: "HEPH", text: "An answer read long ago." }).createdAt;
	renderRouteAtWithRouter("/w/acme/teams");
	const stream = await openStream(acme);
	expect(screen.queryByRole("button", { name: /new answer/u })).toBeNull();

	// Asked and answered in Slack while no event reached this tab.
	const turn = acme.addTurn({ status: "DONE", surface: "SLACK" });
	acme.add({ kind: "USER", surface: "SLACK", text: "Asked from Slack", turnId: turn.id });
	acme.add({ kind: "HEPH", surface: "SLACK", text: "Answered in Slack.", turnId: turn.id });
	act(() => {
		stream.emit("snapshot", acme.conversation());
	});
	await user.click(await screen.findByRole("button", { name: "Ask Hephaestus, new answer" }));
	await screen.findByText("Answered in Slack.");
	await user.keyboard("{Escape}");
	await screen.findByRole("button", { name: "Ask Hephaestus" });
});

it("marks the launcher on reconnect for the first answer ever, written while the stream was down", async () => {
	const { acme } = mockConversationPanel(conversationReady());
	renderRouteAtWithRouter("/w/acme/teams");
	// The first snapshot names no answer: nothing has been answered yet.
	const stream = await openStream(acme);

	const turn = acme.addTurn({ status: "DONE" });
	acme.add({ kind: "USER", text: "My first question", turnId: turn.id });
	acme.add({ kind: "HEPH", text: "My first answer.", turnId: turn.id });
	act(() => {
		stream.emit("snapshot", acme.conversation());
	});
	await screen.findByRole("button", { name: "Ask Hephaestus, new answer" });
});

it("leaves the launcher unmarked on reconnect when the only new answer was deleted", async () => {
	const { acme } = mockConversationPanel(conversationReady());
	acme.lastReadAt = acme.add({ kind: "HEPH", text: "An answer read long ago." }).createdAt;
	renderRouteAtWithRouter("/w/acme/teams");
	const stream = await openStream(acme);

	const answer = acme.add({ kind: "HEPH", text: "Deleted before this tab saw it." });
	answer.deleted = true;
	await act(async () => {
		stream.emit("snapshot", acme.conversation());
	});
	expect(screen.queryByRole("button", { name: /new answer/u })).toBeNull();
});

it("shares what was read between devices: opening the panel on one clears the dot on the other", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	const answer = acme.add({ kind: "HEPH", text: "An answer nobody read yet." });

	// This device opens the panel, which tells the server how far the developer read.
	renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Ask Hephaestus, new answer" }, ROUTE_RENDER_WAIT),
	);
	await screen.findByText("An answer nobody read yet.");
	await waitFor(() => expect(acme.lastReadAt).toBe(answer.createdAt));
	cleanup();

	// Another device, with its own cache, reads the same marker from the server.
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: /new answer/u })).toBeNull();
});

it("clears the dot when another device reads the answer and this one reconnects", async () => {
	const { acme } = mockConversationPanel(conversationReady());
	const answer = acme.add({ kind: "HEPH", text: "An answer nobody read yet." });
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("button", { name: "Ask Hephaestus, new answer" }, ROUTE_RENDER_WAIT);
	const stream = await waitFor(() => {
		const latest = FakeEventSource.latest();
		assert.isDefined(latest);
		return latest;
	});
	acme.lastReadAt = answer.createdAt;
	act(() => {
		stream.open();
		stream.emit("snapshot", acme.conversation());
	});
	await screen.findByRole("button", { name: "Ask Hephaestus" });
});

it("clears the dot live when the developer reads the answer on another device", async () => {
	const { acme } = mockConversationPanel(conversationReady());
	const answer = acme.add({ kind: "HEPH", text: "An answer nobody read yet." });
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("button", { name: "Ask Hephaestus, new answer" }, ROUTE_RENDER_WAIT);
	const stream = await waitFor(() => {
		const latest = FakeEventSource.latest();
		assert.isDefined(latest);
		return latest;
	});
	act(() => {
		stream.open();
		stream.emit("snapshot", acme.conversation());
	});
	// The other device's read reaches this one as the server's `read` event, with no reconnect.
	acme.lastReadAt = answer.createdAt;
	act(() => {
		stream.emit("read", { lastReadAt: answer.createdAt });
	});
	await screen.findByRole("button", { name: "Ask Hephaestus" });
});

it.each([
	{ pointer: "fine", composerFocused: true },
	{ pointer: "coarse", composerFocused: false },
])(
	"focuses the composer on opening the panel only with a $pointer pointer, and on Reply in thread with any",
	async ({ pointer, composerFocused }) => {
		const media = window.matchMedia.bind(window);
		using _pointerMedia = vi
			.spyOn(window, "matchMedia")
			.mockImplementation((text) =>
				Object.assign(media(text), { matches: text === `(pointer: ${pointer})` }),
			);
		const user = userEvent.setup();
		const { acme } = mockConversationPanel(conversationReady());
		const answer = acme.add({ kind: "HEPH", text: "Three pull requests left the reason out." });
		acme.lastReadAt = answer.createdAt;
		renderRouteAtWithRouter("/w/acme/teams");
		await user.click(
			await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
		);
		const composer = await screen.findByRole("textbox", { name: "Message Hephaestus" });
		const panel = screen.getByRole("dialog");
		await waitFor(() => expect(panel.contains(document.activeElement)).toBe(true));
		// On a touch screen the keyboard stays down over the answer the developer opened the panel to read.
		expect(document.activeElement === composer).toBe(composerFocused);

		// Reply in thread is the developer asking to write, so its composer takes focus on any pointer.
		await user.click(screen.getByRole("button", { name: /^Reply in thread \(/u }));
		const reply = await screen.findByRole("textbox", { name: "Reply in thread" });
		await waitFor(() => expect(document.activeElement).toBe(reply));
	},
);

it("closes the panel when a link in an answer opens another page, which is then in front and usable", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/items/:itemId/reviews", () =>
			HttpResponse.json({
				content: [detailReview],
				number: 0,
				size: 10,
				totalElements: 1,
				totalPages: 1,
				last: true,
			}),
		),
	);
	acme.add({ kind: "USER", text: "What should I fix in my pull request?" });
	const answer = acme.add({
		kind: "HEPH",
		text: "One thing stands out.",
		links: [
			{
				id: "f8000000-0000-4000-8000-000000000002",
				kind: "FEEDBACK",
				text: "Add one line on the trade-off before you merge.",
				itemId: 433,
			},
		],
	});
	acme.lastReadAt = answer.createdAt;
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	const stream = await openStream(acme);
	await user.click(screen.getByRole("button", { name: "Ask Hephaestus" }));
	await user.click(await screen.findByRole("link", { name: "Your feedback on this work" }));

	await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/feedback/433"));
	await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	// The page the link named, with no panel left over it.
	await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
	screen.getByRole("heading", { name: "Your feedback on pull request #902" });

	// The panel closed but stayed this workspace's: the conversation is there again on the same stream.
	await user.click(screen.getByRole("button", { name: "Ask Hephaestus" }));
	await screen.findByText("One thing stands out.");
	expect(FakeEventSource.latest()).toBe(stream);
});

/** What the developer can see and reach in the panel: the conversation under an open thread is inert. */
function reachable(elements: HTMLElement[]): HTMLElement[] {
	return elements.filter((element) => element.closest("[inert]") === null);
}

/** The reviews of the work an answer's feedback link opens. */
function serveReviews() {
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/items/:itemId/reviews", () =>
			HttpResponse.json({
				content: [detailReview],
				number: 0,
				size: 10,
				totalElements: 1,
				totalPages: 1,
				last: true,
			}),
		),
	);
}

it.each([
	{
		closing: "closing the panel",
		close: async (user: ReturnType<typeof userEvent.setup>) => {
			// Close, not Escape: Escape in a thread goes back to the conversation first.
			await user.click(screen.getByRole("button", { name: "Close Ask Hephaestus" }));
			// Closing hands focus back to the launcher, and nothing in the closing panel takes it.
			await waitFor(() =>
				expect(document.activeElement).toBe(screen.getByRole("button", { name: "Ask Hephaestus" })),
			);
		},
	},
	{
		closing: "following a link in the thread",
		close: async (user: ReturnType<typeof userEvent.setup>) => {
			serveReviews();
			const [link] = reachable(screen.getAllByRole("link", { name: "Your feedback on this work" }));
			assert.isDefined(link);
			await user.click(link);
			await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		},
	},
])(
	"reopens the thread the developer replied in without raising a touch keyboard, after $closing",
	async ({ close }) => {
		const media = window.matchMedia.bind(window);
		using _pointerMedia = vi
			.spyOn(window, "matchMedia")
			.mockImplementation((text) =>
				Object.assign(media(text), { matches: text === "(pointer: coarse)" }),
			);
		const user = userEvent.setup();
		const { acme } = mockConversationPanel(conversationReady());
		const answer = acme.add({
			kind: "HEPH",
			text: "One thing stands out.",
			links: [
				{
					id: "f8000000-0000-4000-8000-000000000002",
					kind: "FEEDBACK",
					text: "Add one line on the trade-off before you merge.",
					itemId: 433,
				},
			],
		});
		acme.lastReadAt = answer.createdAt;
		renderRouteAtWithRouter("/w/acme/teams");
		await user.click(
			await screen.findByRole("button", { name: "Ask Hephaestus" }, ROUTE_RENDER_WAIT),
		);
		// Reply in thread is the developer asking to write, so it raises the keyboard once.
		await user.click(await screen.findByRole("button", { name: /^Reply in thread \(/u }));
		const replying = await screen.findByRole("textbox", { name: "Reply in thread" });
		await waitFor(() => expect(document.activeElement).toBe(replying));

		await close(user);
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

		await user.click(screen.getByRole("button", { name: "Ask Hephaestus" }));
		const panel = await screen.findByRole("dialog");
		// The thread stays open across closes, but opening the panel again is an ordinary open.
		const reply = await within(panel).findByRole("textbox", { name: "Reply in thread" });
		await waitFor(() => expect(panel.contains(document.activeElement)).toBe(true));
		expect(document.activeElement).not.toBe(reply);
	},
);

const PAUSED = "Live updates paused. Reconnecting…";

it("says in an open thread that live updates are paused, where the conversation under it is hidden", async () => {
	const user = userEvent.setup();
	const { acme } = mockConversationPanel(conversationReady());
	const parent = acme.add({ kind: "HEPH", text: "Three pull requests left the reason out." });
	acme.lastReadAt = parent.createdAt;
	acme.add({ kind: "USER", text: "Which ones?", parentEntryId: parent.id });
	renderRouteAtWithRouter("/w/acme/teams");
	const stream = await openStream(acme);
	await user.click(screen.getByRole("button", { name: "Ask Hephaestus" }));
	await user.click(await screen.findByRole("button", { name: /^1 reply/u }));
	const panel = screen.getByRole("dialog");
	await within(panel).findByText("Which ones?");

	act(() => stream.error());
	act(() => stream.error());
	await waitFor(() => expect(reachable(within(panel).queryAllByText(PAUSED))).toHaveLength(1));
	act(() => stream.open());
	await waitFor(() => expect(within(panel).queryAllByText(PAUSED)).toStrictEqual([]));
});
