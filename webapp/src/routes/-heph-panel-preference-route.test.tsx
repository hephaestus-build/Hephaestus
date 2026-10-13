import { act, cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { assert, expect, it, vi } from "vitest";

import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { FakeEventSource } from "@/test/fake-event-source";
import { MentorFake } from "@/test/mentor-server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

function mockHephPanel(preference: WorkspaceOnboarding) {
	const conversations = { acme: new MentorFake("acme"), other: new MentorFake("other") };
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
		),
		// The account carries no flags: the Heph panel follows the workspace and the member's AI choice.
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
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
	mockHephPanel(preference);
	const { queryClient } = renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
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
	mockHephPanel({
		...workspaceOnboarding(),
		aiChoice: "CLOUD",
		mentorRefusal: "UNAVAILABLE",
		aiOptions: [{ choice: "CLOUD", mentorReady: false, practiceReviewsReady: true, models: [] }],
	});
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
});

it("keeps each workspace's conversation in its own panel", async () => {
	const user = userEvent.setup();
	const { acme, other } = mockHephPanel(hephReady());
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await user.type(
		await screen.findByRole("textbox", { name: "Message Heph" }),
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
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await user.type(
		await screen.findByRole("textbox", { name: "Message Heph" }),
		"Other workspace conversation{Enter}",
	);
	await waitFor(() => expect(other.submissions).toHaveLength(1));
	expect(screen.queryByText("Acme-only conversation")).toBeNull();
	expect(acme.submissions).toHaveLength(1);
});

it("leaves an unsent draft behind in its own workspace", async () => {
	const user = userEvent.setup();
	const { acme, other } = mockHephPanel(hephReady());
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await user.type(
		await screen.findByRole("textbox", { name: "Message Heph" }),
		"Private draft from acme",
	);
	await act(async () => {
		await router.navigate({ to: "/w/$workspaceSlug/teams", params: { workspaceSlug: "other" } });
	});
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	const composer = await screen.findByRole("textbox", { name: "Message Heph" }, ROUTE_RENDER_WAIT);
	expect(composer).toHaveProperty("value", "");
	await user.type(composer, "{Enter}");
	expect(other.submissions).toStrictEqual([]);
	expect(acme.submissions).toStrictEqual([]);
});

it("keeps setup free of the floating composer even when AI is available", async () => {
	mockHephPanel({
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

it("shares the conversation with the mentor page and sends the page it was asked from", async () => {
	const user = userEvent.setup();
	const { acme } = mockHephPanel(hephReady());
	acme.add({ kind: "HEPH", text: "Earlier in this conversation." });
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);
	await screen.findByText("Earlier in this conversation.");
	screen.getByText("From Teams");
	await user.type(
		screen.getByRole("textbox", { name: "Message Heph" }),
		"Who is on my team?{Enter}",
	);
	await waitFor(() =>
		expect(acme.submissions.map(({ text, pageContext }) => ({ text, pageContext }))).toStrictEqual([
			{ text: "Who is on my team?", pageContext: "Teams" },
		]),
	);

	await user.click(screen.getByRole("button", { name: "Do not send this page with your message" }));
	await user.type(screen.getByRole("textbox", { name: "Message Heph" }), "Without the page{Enter}");
	await waitFor(() => expect(acme.submissions).toHaveLength(2));
	expect(acme.submissions[1]?.pageContext).toBeUndefined();

	await user.click(screen.getByRole("button", { name: "Open the Heph page" }));
	await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/mentor"));
	await screen.findByText("Who is on my team?");
	// The page is the conversation, so the launcher stays away from it.
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
});

it("opens a thread as a view over the conversation, and carries it to the mentor page", async () => {
	const user = userEvent.setup();
	const { acme } = mockHephPanel(hephReady());
	const parent = acme.add({ kind: "HEPH", text: "Three pull requests left the reason out." });
	acme.add({ kind: "USER", text: "Which ones?", parentEntryId: parent.id });
	const { router } = renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
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
	await user.click(screen.getByRole("button", { name: "Open the Heph page" }));
	await waitFor(() => expect(router.state.location.search).toMatchObject({ thread: parent.id }));
});

/** The live stream the closed panel's launcher keeps open, after its first snapshot. */
async function openStream(fake: MentorFake): Promise<FakeEventSource> {
	await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT);
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

it("marks the launcher when an answer lands while the panel is closed, and clears it when the panel opens", async () => {
	const user = userEvent.setup();
	const { acme } = mockHephPanel(hephReady());
	const question = acme.add({ kind: "USER", text: "How did my week go?" });
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT);
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
	await user.click(await screen.findByRole("button", { name: "Open Heph, AI mentor, new answer" }));
	await screen.findByText("You opened two pull requests.");
	await user.keyboard("{Escape}");
	await screen.findByRole("button", { name: "Open Heph, AI mentor" });
	expect(screen.queryByRole("button", { name: /new answer/u })).toBeNull();
});

it("leaves the launcher unmarked for a question the developer asked in Slack", async () => {
	const { acme } = mockHephPanel(hephReady());
	renderRouteAtWithRouter("/w/acme/teams");
	const stream = await openStream(acme);
	const question = acme.add({ kind: "USER", surface: "SLACK", text: "Asked from Slack" });
	await act(async () => {
		stream.emit("entry", question);
	});
	expect(screen.queryByRole("button", { name: /new answer/u })).toBeNull();
	screen.getByRole("button", { name: "Open Heph, AI mentor" });
});

it("marks the launcher when Heph answers in a thread", async () => {
	const { acme } = mockHephPanel(hephReady());
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
	await screen.findByRole("button", { name: "Open Heph, AI mentor, new answer" });
});

it("marks the launcher on reconnect for a whole Slack turn that ran while the stream was down, and opening clears it", async () => {
	const user = userEvent.setup();
	const { acme } = mockHephPanel(hephReady());
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
	await user.click(await screen.findByRole("button", { name: "Open Heph, AI mentor, new answer" }));
	await screen.findByText("Answered in Slack.");
	await user.keyboard("{Escape}");
	await screen.findByRole("button", { name: "Open Heph, AI mentor" });
});

it("marks the launcher on reconnect for the first answer ever, written while the stream was down", async () => {
	const { acme } = mockHephPanel(hephReady());
	renderRouteAtWithRouter("/w/acme/teams");
	// The first snapshot names no answer: nothing has been answered yet.
	const stream = await openStream(acme);

	const turn = acme.addTurn({ status: "DONE" });
	acme.add({ kind: "USER", text: "My first question", turnId: turn.id });
	acme.add({ kind: "HEPH", text: "My first answer.", turnId: turn.id });
	act(() => {
		stream.emit("snapshot", acme.conversation());
	});
	await screen.findByRole("button", { name: "Open Heph, AI mentor, new answer" });
});

it("leaves the launcher unmarked on reconnect when the only new answer was deleted", async () => {
	const { acme } = mockHephPanel(hephReady());
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
	const { acme } = mockHephPanel(hephReady());
	const answer = acme.add({ kind: "HEPH", text: "An answer nobody read yet." });

	// This device opens the panel, which tells the server how far the developer read.
	renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole(
			"button",
			{ name: "Open Heph, AI mentor, new answer" },
			ROUTE_RENDER_WAIT,
		),
	);
	await screen.findByText("An answer nobody read yet.");
	await waitFor(() => expect(acme.lastReadAt).toBe(answer.createdAt));
	cleanup();

	// Another device, with its own cache, reads the same marker from the server.
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("button", { name: /new answer/u })).toBeNull();
});

it("clears the dot when another device reads the answer and this one reconnects", async () => {
	const { acme } = mockHephPanel(hephReady());
	const answer = acme.add({ kind: "HEPH", text: "An answer nobody read yet." });
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole(
		"button",
		{ name: "Open Heph, AI mentor, new answer" },
		ROUTE_RENDER_WAIT,
	);
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
	await screen.findByRole("button", { name: "Open Heph, AI mentor" });
});

it("clears the dot live when the developer reads the answer on another device", async () => {
	const { acme } = mockHephPanel(hephReady());
	const answer = acme.add({ kind: "HEPH", text: "An answer nobody read yet." });
	renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole(
		"button",
		{ name: "Open Heph, AI mentor, new answer" },
		ROUTE_RENDER_WAIT,
	);
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
	await screen.findByRole("button", { name: "Open Heph, AI mentor" });
});
