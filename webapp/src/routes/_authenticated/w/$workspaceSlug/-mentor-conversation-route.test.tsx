import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type PathParams } from "msw";
import { afterEach, assert, expect, it, vi } from "vitest";

import type { MentorTurnRequest } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { FakeEventSource } from "@/test/fake-event-source";
import { MentorFake } from "@/test/mentor-server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

/** The workspaces of `fakes`, each with its own conversation, for a member whose Heph can answer. */
function serve(...fakes: MentorFake[]) {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json(fakes.map(({ workspaceSlug }) => workspaceListItem(workspaceSlug))),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () =>
			HttpResponse.json({
				...workspaceOnboarding(),
				aiChoice: "IN_HOUSE_ONLY",
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", mentorReady: true, practiceReviewsReady: true, models: [] },
				],
			}),
		),
		...fakes.flatMap((fake) => fake.handlers()),
	);
}

afterEach(() => {
	server.events.removeAllListeners();
});

/** The live stream the page opened, once it has opened one. */
async function liveStream(): Promise<FakeEventSource> {
	return waitFor(() => {
		const stream = FakeEventSource.latest();
		assert.isDefined(stream);
		return stream;
	}, ROUTE_RENDER_WAIT);
}

/** Heph's answer while it streams: each new word is its own element, so match the whole paragraph. */
function streamedText(text: string) {
	return (_content: string, element: Element | null) =>
		element?.tagName === "P" && element.textContent === text;
}

/** The `before` cursor of each read of the pages whose path ends with `path`, in order. */
function recordPageReads(path: string): (string | null)[] {
	const cursors: (string | null)[] = [];
	server.events.on("request:start", ({ request }) => {
		const url = new URL(request.url);
		if (url.pathname.endsWith(path)) {
			cursors.push(url.searchParams.get("before"));
		}
	});
	return cursors;
}

it("leaves an unsent draft behind in its own workspace", async () => {
	const acme = new MentorFake("acme");
	const beta = new MentorFake("beta");
	serve(acme, beta);

	const { router } = renderRouteAtWithRouter("/w/acme/mentor");
	await userEvent.type(
		await screen.findByRole("textbox", { name: "Message Heph" }, ROUTE_RENDER_WAIT),
		"Private draft from acme",
	);
	await act(async () => {
		await router.navigate({ to: "/w/$workspaceSlug/mentor", params: { workspaceSlug: "beta" } });
	});
	await waitFor(() => expect(router.state.location.pathname).toBe("/w/beta/mentor"));
	const composer = await screen.findByRole("textbox", { name: "Message Heph" });
	expect(composer).toHaveProperty("value", "");
	await userEvent.type(composer, "{Enter}");
	expect(beta.submissions).toStrictEqual([]);
});

it("opens at the newest messages and reads older ones above them", async () => {
	const fake = new MentorFake("acme", { pageSize: 2 });
	fake.add({ kind: "USER", text: "The oldest question" });
	fake.add({ kind: "HEPH", text: "A middle answer" });
	fake.add({ kind: "USER", text: "The newest question" });
	const reads = recordPageReads("/mentor/entries");
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("The newest question", {}, ROUTE_RENDER_WAIT);
	screen.getByText("A middle answer");
	await userEvent.click(screen.getByRole("button", { name: "Show earlier messages" }));
	await screen.findByText("The oldest question");
	expect(reads).toStrictEqual([null, "2"]);
});

it("brings a linked message into view on a cold load, reading older pages until it arrives", async () => {
	const fake = new MentorFake("acme", { pageSize: 2 });
	const linked = fake.add({ kind: "HEPH", text: "The answer the digest links to" });
	fake.add({ kind: "USER", text: "A later question" });
	fake.add({ kind: "HEPH", text: "A later answer" });
	fake.add({ kind: "USER", text: "The newest question" });
	fake.add({ kind: "HEPH", text: "The newest answer" });
	const reads = recordPageReads("/mentor/entries");
	serve(fake);

	renderRouteAtWithRouter(`/w/acme/mentor?entry=${linked.id}`);
	await screen.findByText("The answer the digest links to", {}, ROUTE_RENDER_WAIT);
	expect(reads).toStrictEqual([null, "4", "2"]);
});

it("says when a linked message is not in the conversation, after reading every older page", async () => {
	const fake = new MentorFake("acme", { pageSize: 2 });
	fake.add({ kind: "USER", text: "The oldest question" });
	fake.add({ kind: "HEPH", text: "A later answer" });
	fake.add({ kind: "USER", text: "The newest question" });
	const reads = recordPageReads("/mentor/entries");
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor?entry=e9999999-0000-4000-8000-000000000000");
	await screen.findByText(
		"We could not find that message. It may have been deleted.",
		{},
		ROUTE_RENDER_WAIT,
	);
	expect(reads).toStrictEqual([null, "2"]);
});

it("brings a linked reply into view inside its thread, reading older replies until it arrives", async () => {
	const fake = new MentorFake("acme", { pageSize: 1 });
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	const linked = fake.add({
		kind: "HEPH",
		text: "Share blockers earlier next time.",
		parentEntryId: parent.id,
	});
	fake.add({ kind: "USER", text: "Why?", parentEntryId: parent.id });
	fake.add({ kind: "HEPH", text: "Two pull requests waited a day.", parentEntryId: parent.id });
	const reads = recordPageReads(`/entries/${parent.id}/thread`);
	serve(fake);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}&entry=${linked.id}`);
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(panel).findByText("Share blockers earlier next time.");
	expect(reads).toStrictEqual([null, "3", "2"]);
});

it("shows a sent message at once and keeps its request id when a failed send is retried", async () => {
	const fake = new MentorFake();
	serve(fake);
	const held = deferred();
	const attempts: MentorTurnRequest[] = [];
	server.use(
		http.post<PathParams, MentorTurnRequest>(
			"*/workspaces/acme/mentor/turns",
			async ({ request }) => {
				attempts.push(await request.json());
				await held.promise;
				return HttpResponse.json(
					{
						type: "about:blank",
						status: 409,
						detail: "Heph is still answering. Wait for an answer before you send more messages.",
					},
					{ status: 409 },
				);
			},
			{ once: true },
		),
	);

	renderRouteAtWithRouter("/w/acme/mentor");
	await userEvent.type(
		await screen.findByRole("textbox", { name: "Message Heph" }, ROUTE_RENDER_WAIT),
		"Is my description clear?{Enter}",
	);
	// On screen before the server answers.
	await screen.findByText("Is my description clear?");
	held.resolve();

	await screen.findByText(
		"Not sent. Heph is still answering. Wait for an answer before you send more messages.",
	);
	await userEvent.click(screen.getByRole("button", { name: /^Retry \(/u }));
	await waitFor(() => expect(fake.submissions).toHaveLength(1));
	expect(fake.submissions[0]?.requestId).toBe(attempts[0]?.requestId);
	await waitFor(() => expect(screen.queryByText(/Not sent/u)).toBeNull());
	expect(screen.getAllByText("Is my description clear?")).toHaveLength(1);
});

it("sends a follow-up while Heph answers, and stops the running turn", async () => {
	const fake = new MentorFake();
	const running = fake.addTurn({
		status: "RUNNING",
		partial: { activity: "WRITING", stage: "Thinking…", text: "Your last pull request" },
	});
	fake.add({ kind: "USER", text: "What about my last pull request?", turnId: running.id });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText(streamedText("Your last pull request"), {}, ROUTE_RENDER_WAIT);
	await userEvent.type(
		screen.getByRole("textbox", { name: "Message Heph" }),
		"And the one before?{Enter}",
	);
	await waitFor(() =>
		expect(fake.submissions.map(({ text }) => text)).toStrictEqual(["And the one before?"]),
	);
	await screen.findByText("Queued. Heph answers this next.");

	await userEvent.click(
		within(screen.getByRole("log")).getByRole("button", { name: /^Stop Heph \(/u }),
	);
	await waitFor(() => expect(fake.stops).toStrictEqual([running.id]));
});

it("votes on Heph's message, and clears the vote when the pressed vote is pressed again", async () => {
	const fake = new MentorFake();
	const answer = fake.add({ kind: "HEPH", text: "Link the issue in the description." });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	const helpful = await screen.findByRole("button", { name: /^Helpful/u }, ROUTE_RENDER_WAIT);
	await userEvent.click(helpful);
	await waitFor(() =>
		expect(screen.getByRole("button", { name: /^Helpful/u }).getAttribute("aria-pressed")).toBe(
			"true",
		),
	);
	await userEvent.click(screen.getByRole("button", { name: /^Helpful/u }));
	await waitFor(() =>
		expect(fake.votes).toStrictEqual([
			{ entryId: answer.id, vote: "HELPFUL" },
			{ entryId: answer.id, vote: null },
		]),
	);
	expect(screen.getByRole("button", { name: /^Helpful/u }).getAttribute("aria-pressed")).toBe(
		"false",
	);
	expect(screen.getByRole("button", { name: /^Not helpful/u }).getAttribute("aria-pressed")).toBe(
		"false",
	);
});

it("takes an optimistic vote back when the server refuses it", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "HEPH", text: "Link the issue in the description." });
	serve(fake);
	server.use(
		http.put("*/workspaces/acme/mentor/entries/:entryId/vote", () =>
			HttpResponse.json({ status: 503 }, { status: 503 }),
		),
	);

	renderRouteAtWithRouter("/w/acme/mentor");
	await userEvent.click(
		await screen.findByRole("button", { name: /^Helpful/u }, ROUTE_RENDER_WAIT),
	);
	await screen.findByText("We could not save your vote. Try again.");
	expect(screen.getByRole("button", { name: /^Helpful/u }).getAttribute("aria-pressed")).toBe(
		"false",
	);
});

it("deletes the developer's own message once they confirm", async () => {
	const fake = new MentorFake();
	const question = fake.add({ kind: "USER", text: "Something I should not have pasted" });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await userEvent.click(
		await screen.findByRole("button", { name: /^Delete message/u }, ROUTE_RENDER_WAIT),
	);
	const dialog = await screen.findByRole("alertdialog", { name: "Delete this message?" });
	await userEvent.click(within(dialog).getByRole("button", { name: "Delete message" }));
	await screen.findByText("This message was deleted.");
	expect(fake.deletes).toStrictEqual([question.id]);
	expect(screen.queryByText("Something I should not have pasted")).toBeNull();
});

it("opens the thread named in the address and answers in it", async () => {
	const fake = new MentorFake();
	const parent = fake.add({ kind: "HEPH", text: "Three pull requests left the reason out." });
	fake.add({ kind: "USER", text: "Which ones?", parentEntryId: parent.id });
	serve(fake);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(panel).findByText("Which ones?");
	within(panel).getByText("Three pull requests left the reason out.");
	expect(screen.getByRole("button", { name: /^1 reply/u }).getAttribute("aria-expanded")).toBe(
		"true",
	);

	await userEvent.type(
		within(panel).getByRole("textbox", { name: "Reply in thread" }),
		"Thanks{Enter}",
	);
	await waitFor(() =>
		expect(
			fake.submissions.map(({ text, parentEntryId }) => ({ text, parentEntryId })),
		).toStrictEqual([{ text: "Thanks", parentEntryId: parent.id }]),
	);
	await within(panel).findByText("Thanks");

	await userEvent.click(within(panel).getByRole("button", { name: "Close thread" }));
	await waitFor(() => expect(screen.queryByRole("complementary", { name: "Thread" })).toBeNull());
});

it("counts every reply above the open thread and on its message, from Slack and from the panel", async () => {
	const fake = new MentorFake();
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	fake.add({ kind: "HEPH", text: "Share blockers earlier.", parentEntryId: parent.id });
	serve(fake);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(panel).findByText("1 reply");
	const stream = await liveStream();
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
	});
	const live = fake.add({
		kind: "HEPH",
		surface: "SLACK",
		text: "A second reply from Slack.",
		parentEntryId: parent.id,
	});
	act(() => {
		stream.emit("entry", live);
	});
	await within(panel).findByText("2 replies");
	screen.getByRole("button", { name: /^2 replies/u });

	await userEvent.type(
		within(panel).getByRole("textbox", { name: "Reply in thread" }),
		"Mine{Enter}",
	);
	await within(panel).findByText("3 replies");
	screen.getByRole("button", { name: /^3 replies/u });
});

/** The open thread's reply field, once the thread shows. */
async function threadReplyField(): Promise<HTMLElement> {
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	return within(panel).findByRole("textbox", { name: "Reply in thread" });
}

it("starts each thread fresh, and gives a thread back the reply the developer was writing there", async () => {
	const fake = new MentorFake();
	const first = fake.add({ kind: "USER", text: "First question" });
	fake.add({ kind: "HEPH", text: "First answer", parentEntryId: first.id });
	const second = fake.add({ kind: "USER", text: "Second question" });
	fake.add({ kind: "HEPH", text: "Second answer", parentEntryId: second.id });
	serve(fake);

	const { router } = renderRouteAtWithRouter(`/w/acme/mentor?thread=${first.id}`);
	await userEvent.type(await threadReplyField(), "Half a reply to the first");

	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/mentor",
			params: { workspaceSlug: "acme" },
			search: { thread: second.id },
		});
	});
	await screen.findByText("Second answer");
	await expect(threadReplyField()).resolves.toHaveProperty("value", "");

	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/mentor",
			params: { workspaceSlug: "acme" },
			search: { thread: first.id },
		});
	});
	await screen.findByText("First answer");
	await expect(threadReplyField()).resolves.toHaveProperty("value", "Half a reply to the first");
});

it("shows what the live stream reports: Heph's answer as it arrives and a message written in Slack", async () => {
	const fake = new MentorFake();
	const question = fake.add({ kind: "USER", text: "How did my week go?" });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("How did my week go?", {}, ROUTE_RENDER_WAIT);
	const stream = FakeEventSource.latest();
	assert.isDefined(stream);
	expect(stream.url).toMatch(/\/workspaces\/acme\/mentor\/events$/u);
	expect(stream.withCredentials).toBe(true);

	const turn = {
		id: "a9000000-0000-4000-8000-000000000001",
		conversationId: question.conversationId,
		requestId: "b9000000-0000-4000-8000-000000000001",
		surface: "APP",
		createdAt: "2026-10-09T09:10:00Z",
		attempt: 1,
	};
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
		stream.emit("turn", {
			...turn,
			status: "RUNNING",
			partial: { activity: "WRITING", stage: "Thinking…", text: "You opened two" },
		});
	});
	await screen.findByText(streamedText("You opened two"));
	within(screen.getByRole("log")).getByRole("button", { name: /^Stop Heph/u });

	act(() => {
		stream.emit("entry", {
			id: "e9000000-0000-4000-8000-000000000002",
			conversationId: question.conversationId,
			seq: question.seq + 1,
			kind: "HEPH",
			surface: "APP",
			text: "You opened two pull requests this week.",
			turnId: turn.id,
			createdAt: "2026-10-09T09:10:05Z",
			deleted: false,
			observations: [],
		});
		stream.emit("turn", { ...turn, status: "DONE", finishedAt: "2026-10-09T09:10:06Z" });
		stream.emit("entry", {
			id: "e9000000-0000-4000-8000-000000000003",
			conversationId: question.conversationId,
			seq: question.seq + 2,
			kind: "USER",
			surface: "SLACK",
			text: "Asking from Slack now",
			createdAt: "2026-10-09T09:20:00Z",
			deleted: false,
			observations: [],
		});
	});
	await screen.findByText("You opened two pull requests this week.");
	const slackText = await screen.findByText("Asking from Slack now");
	const slackMessage = slackText.closest<HTMLElement>("[data-slot=message]");
	assert.isNotNull(slackMessage);
	within(slackMessage).getByText("Slack");
	// The finished turn leaves no status line and nothing to stop.
	expect(screen.queryByRole("button", { name: /^Stop Heph/u })).toBeNull();
});

it("shows why a turn failed after Heph wrote part of its answer, and asks the question again", async () => {
	const fake = new MentorFake();
	const turn = fake.addTurn({ status: "RUNNING" });
	fake.add({ kind: "USER", text: "How did my week go?", turnId: turn.id });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("How did my week go?", {}, ROUTE_RENDER_WAIT);
	const stream = await liveStream();
	const partAnswer = fake.add({
		kind: "HEPH",
		text: "You opened two pull requests and",
		turnId: turn.id,
	});
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
		stream.emit("entry", partAnswer);
		stream.emit("turn", {
			...turn,
			status: "FAILED",
			error: "The AI model stopped responding. Try again.",
			finishedAt: "2026-10-09T09:10:00Z",
		});
	});

	await screen.findByText("The AI model stopped responding. Try again.");
	await userEvent.click(screen.getByRole("button", { name: /^Try again \(/u }));
	await waitFor(() =>
		expect(fake.submissions.map(({ text }) => text)).toStrictEqual(["How did my week go?"]),
	);
});

it("reads the conversation again when a reconnect's snapshot is ahead of it", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "How did my week go?" });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("How did my week go?", {}, ROUTE_RENDER_WAIT);
	const stream = await liveStream();
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
	});
	// Written while the stream was down, so no entry event carries it.
	fake.add({ kind: "HEPH", text: "Written while you were away" });
	act(() => {
		stream.emit("snapshot", fake.conversation());
	});
	await screen.findByText("Written while you were away");
});

it("keeps every loaded message when a reconnect rereads the conversation after older pages were read", async () => {
	const fake = new MentorFake("acme", { pageSize: 2 });
	fake.add({ kind: "USER", text: "The oldest question" });
	fake.add({ kind: "HEPH", text: "A middle answer" });
	fake.add({ kind: "USER", text: "The newest question" });
	const reads = recordPageReads("/mentor/entries");
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("The newest question", {}, ROUTE_RENDER_WAIT);
	await userEvent.click(screen.getByRole("button", { name: "Show earlier messages" }));
	await screen.findByText("The oldest question");
	const stream = await liveStream();
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
	});
	// The connect's own snapshot rereads what is loaded.
	await waitFor(() => expect(reads).toStrictEqual([null, "2", null, "2"]));
	fake.add({ kind: "HEPH", text: "Written while you were away" });
	act(() => {
		stream.emit("snapshot", fake.conversation());
	});

	await screen.findByText("Written while you were away");
	// The reread starts at the newest page and walks back as far as the developer had read.
	await waitFor(() => expect(reads).toStrictEqual([null, "2", null, "2", null, "3"]));
	for (const text of ["The oldest question", "A middle answer", "The newest question"]) {
		screen.getByText(text);
	}
	expect(screen.queryByRole("button", { name: "Show earlier messages" })).toBeNull();
});

it("keeps every loaded reply when a reconnect rereads a thread after older replies were read", async () => {
	const fake = new MentorFake("acme", { pageSize: 2 });
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	fake.add({ kind: "HEPH", text: "Share blockers earlier next time.", parentEntryId: parent.id });
	fake.add({ kind: "USER", text: "Why?", parentEntryId: parent.id });
	fake.add({ kind: "HEPH", text: "They waited for review.", parentEntryId: parent.id });
	const reads = recordPageReads(`/entries/${parent.id}/thread`);
	serve(fake);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(panel).findByText("They waited for review.");
	await userEvent.click(within(panel).getByRole("button", { name: "Show earlier messages" }));
	await within(panel).findByText("Share blockers earlier next time.");
	const stream = await liveStream();
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
	});
	// The connect's own snapshot rereads what is loaded.
	await waitFor(() => expect(reads).toStrictEqual([null, "2", null, "2"]));
	fake.add({ kind: "HEPH", text: "Two pull requests waited a day.", parentEntryId: parent.id });
	act(() => {
		stream.emit("snapshot", fake.conversation());
	});

	await within(panel).findByText("Two pull requests waited a day.");
	await waitFor(() => expect(reads).toStrictEqual([null, "2", null, "2", null, "3"]));
	for (const text of ["Share blockers earlier next time.", "Why?", "They waited for review."]) {
		within(panel).getByText(text);
	}
});

it("shows an edit and a deletion made while the stream was down, though no new message moved the newest seq", async () => {
	const fake = new MentorFake();
	const question = fake.add({ kind: "USER", text: "Something I should not have pasted" });
	const answer = fake.add({ kind: "HEPH", text: "The first wording of the answer" });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("The first wording of the answer", {}, ROUTE_RENDER_WAIT);
	const stream = await liveStream();
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
	});
	// Written while the stream was down: Slack edited the answer and the developer deleted the question.
	answer.text = "The edited wording of the answer";
	question.deleted = true;
	question.text = "";
	act(() => {
		stream.emit("snapshot", fake.conversation());
	});

	await screen.findByText("The edited wording of the answer");
	await screen.findByText("This message was deleted.");
	expect(screen.queryByText("Something I should not have pasted")).toBeNull();
});

it("keeps a message that arrives live while a reconnect's reread is still answering", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "Before the reconnect" });
	serve(fake);

	const { queryClient } = renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("Before the reconnect", {}, ROUTE_RENDER_WAIT);
	const stream = await liveStream();
	// The reread reads the ledger as it is now, and answers only once the test lets it.
	const answer = deferred();
	const readStarted = deferred();
	server.use(
		http.get(
			"*/workspaces/acme/mentor/entries",
			async () => {
				const page = { entries: fake.entries.filter((entry) => entry.parentEntryId === undefined) };
				readStarted.resolve();
				await answer.promise;
				return HttpResponse.json(page);
			},
			{ once: true },
		),
	);
	act(() => {
		stream.open();
		stream.emit("snapshot", fake.conversation());
	});
	await readStarted.promise;
	const live = fake.add({ kind: "HEPH", text: "Arrived over the live stream" });
	act(() => {
		stream.emit("entry", live);
	});
	answer.resolve();

	await waitFor(() => expect(queryClient.isFetching()).toBe(0));
	await screen.findByText("Arrived over the live stream");
	screen.getByText("Before the reconnect");
});

it("shows no phantom turn when a send's answer lands after Heph already answered, while a reconnect's reread is answering", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "Before the reconnect" });
	serve(fake);
	const postStarted = deferred();
	const postAnswer = deferred();
	server.use(
		http.post<PathParams, MentorTurnRequest>(
			"*/workspaces/acme/mentor/turns",
			async ({ request }) => {
				const body = await request.json();
				const turn = fake.addTurn({ status: "QUEUED", requestId: body.requestId });
				const question = fake.add({
					kind: "USER",
					text: body.text,
					requestId: body.requestId,
					turnId: turn.id,
				});
				// The answer as the server wrote it at submit time.
				const submission = { turn: { ...turn }, question: { ...question } };
				// Heph answers and the turn ends before the answer to the send reaches the browser.
				turn.status = "DONE";
				turn.attempt = 1;
				question.turnStatus = "DONE";
				fake.add({ kind: "HEPH", text: "Heph's answer", turnId: turn.id });
				postStarted.resolve();
				await postAnswer.promise;
				return HttpResponse.json(submission);
			},
			{ once: true },
		),
	);

	const { queryClient } = renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("Before the reconnect", {}, ROUTE_RENDER_WAIT);
	const stream = await liveStream();
	act(() => stream.open());
	await userEvent.type(screen.getByRole("textbox", { name: "Message Heph" }), "Hello{Enter}");
	await postStarted.promise;

	// The reconnect's reread is held until the late answer to the send has landed.
	const pagesAnswer = deferred();
	const pagesStarted = deferred();
	server.use(
		http.get(
			"*/workspaces/acme/mentor/entries",
			async () => {
				const page = { entries: fake.entries.filter((entry) => entry.parentEntryId === undefined) };
				pagesStarted.resolve();
				await pagesAnswer.promise;
				return HttpResponse.json(page);
			},
			{ once: true },
		),
	);
	act(() => {
		stream.emit("snapshot", fake.conversation());
	});
	await pagesStarted.promise;
	postAnswer.resolve();
	await waitFor(() => expect(queryClient.isMutating()).toBe(0));
	pagesAnswer.resolve();

	await screen.findByText("Heph's answer");
	await waitFor(() => expect(queryClient.isFetching()).toBe(0));
	expect(screen.queryByRole("button", { name: /^Stop/u })).toBeNull();
	expect(screen.queryByText(/Queued/u)).toBeNull();
	expect(screen.queryByText("Thinking…", { selector: "p" })).toBeNull();
});

it("says live updates are paused while the browser's own reconnects keep failing, and clears it once one opens", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "How did my week go?" });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("How did my week go?", {}, ROUTE_RENDER_WAIT);
	const stream = await liveStream();
	act(() => stream.open());
	act(() => stream.error());
	expect(screen.queryByText("Live updates paused. Reconnecting…")).toBeNull();
	act(() => stream.error());
	await screen.findByText("Live updates paused. Reconnecting…");
	act(() => stream.open());
	await waitFor(() => expect(screen.queryByText("Live updates paused. Reconnecting…")).toBeNull());
});

it("offers Retry in a linked thread when the conversation's state cannot be read, and shows the thread after it", async () => {
	const fake = new MentorFake();
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	fake.add({ kind: "HEPH", text: "Share blockers earlier.", parentEntryId: parent.id });
	serve(fake);
	server.use(
		http.get("*/workspaces/acme/mentor/conversation", () =>
			HttpResponse.json({ status: 503 }, { status: 503 }),
		),
	);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	const retry = await within(panel).findByRole("button", { name: "Retry" });
	server.use(
		http.get("*/workspaces/acme/mentor/conversation", () => HttpResponse.json(fake.conversation())),
	);
	await userEvent.click(retry);
	await within(panel).findByText("Share blockers earlier.");
});

it("reads the conversation again when a live event cannot be read", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "How did my week go?" });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("How did my week go?", {}, ROUTE_RENDER_WAIT);
	const stream = await liveStream();
	fake.add({ kind: "HEPH", text: "An answer in a shape this tab does not know" });
	act(() => {
		stream.open();
		stream.emit("entry", { kind: "TOOL_RESULT" });
	});
	await screen.findByText("An answer in a shape this tab does not know");
});

it("nests Heph's headings and a digest's sections under the page's headings without skipping a level", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "HEPH", text: "## Why\n\nThe reviewer could not see the reason." });
	fake.add({
		kind: "DIGEST",
		surface: "SYSTEM",
		text: "Weekly digest for 5–11 October 2026.",
		digest: {
			weekStart: "2026-10-05",
			windowStart: "2026-10-04T22:00:00Z",
			windowEnd: "2026-10-11T22:00:00Z",
			timeZone: "Europe/Berlin",
			feedback: [
				{
					feedbackId: "f8000000-0000-4000-8000-000000000004",
					practiceSlug: "share-blockers",
					practiceName: "Share blockers early",
					channel: "IN_APP",
					title: "Name the blocker in your stand-up",
					deliveredAt: "2026-10-08T10:00:00Z",
					withdrawn: false,
					replaced: false,
				},
			],
			changes: [],
			waiting: 0,
		},
	});
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByRole("heading", { name: "Feedback that reached you" }, ROUTE_RENDER_WAIT);

	const levels = screen
		.getAllByRole("heading")
		.map((heading) => ({ name: heading.textContent, level: Number(heading.tagName.slice(1)) }));
	expect(levels).toStrictEqual([
		{ name: "Heph", level: 1 },
		{ name: "Conversation with Heph", level: 2 },
		{ name: "Why", level: 3 },
		{ name: "Feedback that reached you", level: 3 },
	]);
});

it("links a weekly digest's feedback to its place in the conversation, and replies in the digest's thread", async () => {
	const fake = new MentorFake();
	const digest = fake.add({
		kind: "DIGEST",
		surface: "SYSTEM",
		text: "Weekly digest for 5–11 October 2026. Use fetch_context weekly_digest.json to read it.",
		digest: {
			weekStart: "2026-10-05",
			windowStart: "2026-10-04T22:00:00Z",
			windowEnd: "2026-10-11T22:00:00Z",
			timeZone: "Europe/Berlin",
			feedback: [
				{
					feedbackId: "f8000000-0000-4000-8000-000000000003",
					practiceSlug: "share-blockers",
					practiceName: "Share blockers early",
					channel: "IN_CHAT",
					mentorEntryId: "e8000000-0000-4000-8000-000000000002",
					mentorParentEntryId: "e8000000-0000-4000-8000-000000000001",
					deliveredAt: "2026-10-08T10:00:00Z",
					withdrawn: false,
					replaced: false,
				},
			],
			changes: [],
			waiting: 0,
		},
	});
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	const link = await screen.findByRole(
		"link",
		{ name: /In your conversation with Heph/u },
		ROUTE_RENDER_WAIT,
	);
	expect(link.getAttribute("href")).toBe(
		"/w/acme/mentor?thread=e8000000-0000-4000-8000-000000000001&entry=e8000000-0000-4000-8000-000000000002",
	);

	await userEvent.click(
		screen.getByRole("button", { name: "Reply in thread (Weekly digest, 5–11 October 2026)" }),
	);
	const panel = await screen.findByRole("complementary", { name: "Thread" });
	await userEvent.type(
		within(panel).getByRole("textbox", { name: "Reply in thread" }),
		"Go through it with me{Enter}",
	);
	await waitFor(() =>
		expect(
			fake.submissions.map(({ text, parentEntryId }) => ({ text, parentEntryId })),
		).toStrictEqual([{ text: "Go through it with me", parentEntryId: digest.id }]),
	);
});
