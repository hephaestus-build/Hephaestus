import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type PathParams } from "msw";
import { afterEach, assert, expect, it, vi } from "vitest";

import type { MentorTurnRequest } from "@/api/types.gen";
import { hephEntriesOptions } from "@/hooks/heph-cache";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { FakeEventSource } from "@/test/fake-event-source";
import { MentorFake } from "@/test/mentor-server";
import { ObserverStub } from "@/test/observers";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

/** The workspaces of `fakes`, each with its own conversation, for a member whose Heph can answer. */
function serve(...fakes: MentorFake[]) {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json(fakes.map(({ workspaceSlug }) => workspaceListItem(workspaceSlug))),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/:workspaceSlug/members/me", () => HttpResponse.json(ownMembership())),
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
	vi.stubGlobal("ResizeObserver", ObserverStub);
	document.documentElement.style.removeProperty("font-size");
});

/** What a browser reports for an element `width` CSS pixels wide. */
function sizeEntry(target: Element, width: number): ResizeObserverEntry {
	const height = 800;
	const box = [{ inlineSize: width, blockSize: height }];
	return {
		target,
		contentRect: {
			x: 0,
			y: 0,
			width,
			height,
			top: 0,
			left: 0,
			right: width,
			bottom: height,
			toJSON: () => ({ width, height }),
		},
		contentBoxSize: box,
		borderBoxSize: box,
		devicePixelContentBoxSize: box,
	};
}

/**
 * jsdom lays nothing out, so the page cannot measure the region its conversation and thread share,
 * and a thread shows as a drawer. This gives the region `width` CSS pixels at a root text size of
 * `textPx`, the browser's default unless the reader changed it: every element observed reports the
 * width once observing starts, as in a browser. The returned function reports a new width, as a
 * window resize or a sidebar toggle does.
 */
function giveRoom(width: number, textPx = 16): (next: number) => void {
	document.documentElement.style.fontSize = `${textPx}px`;
	let current = width;
	const observed: {
		onResize: ResizeObserverCallback;
		observer: ResizeObserver;
		target: Element;
	}[] = [];
	class ReportingResizeObserver implements ResizeObserver {
		readonly #onResize: ResizeObserverCallback;
		constructor(onResize: ResizeObserverCallback) {
			this.#onResize = onResize;
		}
		observe(target: Element) {
			observed.push({ onResize: this.#onResize, observer: this, target });
			this.#onResize([sizeEntry(target, current)], this);
		}
		unobserve = vi.fn();
		disconnect = vi.fn();
	}
	vi.stubGlobal("ResizeObserver", ReportingResizeObserver);
	return (next) => {
		current = next;
		act(() => {
			for (const { onResize, observer, target } of observed) {
				onResize([sizeEntry(target, next)], observer);
			}
		});
	};
}

/** Room for the thread's 26rem column beside a 24rem conversation, at the default 16px text. */
const ROOMY = 1024;

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

it("says older messages are loading once Retry reads them again after they failed", async () => {
	const fake = new MentorFake("acme", { pageSize: 2 });
	fake.add({ kind: "USER", text: "The oldest question" });
	fake.add({ kind: "HEPH", text: "A middle answer" });
	fake.add({ kind: "USER", text: "The newest question" });
	serve(fake);
	const held = deferred();

	renderRouteAtWithRouter("/w/acme/mentor");
	try {
		await screen.findByText("The newest question", {}, ROUTE_RENDER_WAIT);
		server.use(
			http.get(
				"*/workspaces/acme/mentor/entries",
				() => HttpResponse.json({ status: 503 }, { status: 503 }),
				{ once: true },
			),
		);
		await userEvent.click(screen.getByRole("button", { name: "Show earlier messages" }));
		const retry = await screen.findByRole("button", { name: "Retry" });
		screen.getByText("We could not load earlier messages.");

		// The read Retry starts is held open, so the line shows what is on screen while it runs.
		server.use(
			http.get(
				"*/workspaces/acme/mentor/entries",
				async () => {
					await held.promise;
					return HttpResponse.json({ entries: fake.entries.slice(0, 1) });
				},
				{ once: true },
			),
		);
		await userEvent.click(retry);
		await screen.findByText("Loading earlier messages…");
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

		held.resolve();
		await screen.findByText("The oldest question");
		expect(screen.queryByText("Loading earlier messages…")).toBeNull();
	} finally {
		held.resolve();
	}
});

it("offers the composer only once the conversation is read, so a message sent from it shows", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "How did my week go?" });
	serve(fake);
	const held = deferred();
	server.use(
		http.get(
			"*/workspaces/acme/mentor/entries",
			async () => {
				await held.promise;
				return HttpResponse.json({ status: 503 }, { status: 503 });
			},
			{ once: true },
		),
	);

	renderRouteAtWithRouter("/w/acme/mentor");
	try {
		// The first read is on the way. A message sent now has no page to land in, so nothing takes one.
		await screen.findByText("Loading the conversation…", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("textbox", { name: "Message Heph" })).toBeNull();

		held.resolve();
		const retry = await screen.findByRole("button", { name: "Retry" });
		screen.getByText("We could not load these messages");
		expect(screen.queryByRole("textbox", { name: "Message Heph" })).toBeNull();
		expect(fake.submissions).toStrictEqual([]);

		await userEvent.click(retry);
		await screen.findByText("How did my week go?");
		await userEvent.type(
			screen.getByRole("textbox", { name: "Message Heph" }),
			"Is my description clear?{Enter}",
		);
		await waitFor(() =>
			expect(fake.submissions.map(({ text }) => text)).toStrictEqual(["Is my description clear?"]),
		);
		await screen.findByText("Is my description clear?");
	} finally {
		held.resolve();
	}
});

it("gives a draft back once Retry reads a conversation that could not be read on the way back to it", async () => {
	const acme = new MentorFake("acme");
	acme.add({ kind: "USER", text: "How did my week go?" });
	const beta = new MentorFake("beta");
	serve(acme, beta);

	const { router, queryClient } = renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("How did my week go?", {}, ROUTE_RENDER_WAIT);
	await userEvent.type(screen.getByRole("textbox", { name: "Message Heph" }), "Half a question");
	await act(async () => {
		await router.navigate({ to: "/w/$workspaceSlug/mentor", params: { workspaceSlug: "beta" } });
	});
	await waitFor(() => expect(router.state.location.pathname).toBe("/w/beta/mentor"));
	await screen.findByRole("textbox", { name: "Message Heph" });

	// Nothing shows acme's messages now. Only their pages leave the cache, as unused data does after
	// a while, so the way back reads them first. The draft is not part of the cache.
	const acmePages = hephEntriesOptions("acme").queryKey;
	queryClient.removeQueries({ queryKey: acmePages, exact: true, type: "inactive" });
	expect(queryClient.getQueryState(acmePages)).toBeUndefined();
	server.use(
		http.get(
			"*/workspaces/acme/mentor/entries",
			() => HttpResponse.json({ status: 503 }, { status: 503 }),
			{ once: true },
		),
	);

	await act(async () => {
		await router.navigate({ to: "/w/$workspaceSlug/mentor", params: { workspaceSlug: "acme" } });
	});
	const retry = await screen.findByRole("button", { name: "Retry" }, ROUTE_RENDER_WAIT);
	screen.getByText("We could not load these messages");
	expect(screen.queryByRole("textbox", { name: "Message Heph" })).toBeNull();

	await userEvent.click(retry);
	await screen.findByText("How did my week go?");
	expect(screen.getByRole("textbox", { name: "Message Heph" })).toHaveProperty(
		"value",
		"Half a question",
	);
	expect(acme.submissions).toStrictEqual([]);
	expect(beta.submissions).toStrictEqual([]);
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
	giveRoom(ROOMY);
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

it("keeps a successful send visible until an in-flight read lets its stored copy land", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "Before the reconnect" });
	serve(fake);
	const postStarted = deferred();
	const postAnswer = deferred();
	const pagesStarted = deferred();
	const pagesAnswer = deferred();
	server.use(
		http.post<PathParams, MentorTurnRequest>(
			"*/workspaces/acme/mentor/turns",
			async ({ request }) => {
				const body = await request.json();
				fake.submissions.push(body);
				const turn = fake.addTurn({ status: "QUEUED", requestId: body.requestId });
				const question = fake.add({
					kind: "USER",
					text: body.text,
					requestId: body.requestId,
					turnId: turn.id,
				});
				postStarted.resolve();
				await postAnswer.promise;
				return HttpResponse.json({ turn, question });
			},
			{ once: true },
		),
	);

	const { queryClient } = renderRouteAtWithRouter("/w/acme/mentor");
	try {
		await screen.findByText("Before the reconnect", {}, ROUTE_RENDER_WAIT);
		const stream = await liveStream();
		act(() => stream.open());
		const composer = screen.getByRole("textbox", { name: "Message Heph" });
		await userEvent.type(composer, "A question that must stay visible{Enter}");
		await postStarted.promise;

		// A read already underway must land before the stored send patches the page.
		server.use(
			http.get(
				"*/workspaces/acme/mentor/entries",
				async () => {
					const page = { entries: [...fake.entries] };
					pagesStarted.resolve();
					await pagesAnswer.promise;
					return HttpResponse.json(page);
				},
				{ once: true },
			),
		);
		act(() => stream.emit("snapshot", fake.conversation()));
		await pagesStarted.promise;
		postAnswer.resolve();
		await waitFor(() => expect(queryClient.isMutating()).toBe(0));

		// The developer can keep writing after the POST has succeeded, while the read still waits.
		await userEvent.type(composer, "The next question is still a draft");
		expect(screen.getAllByText("A question that must stay visible")).toHaveLength(1);
		expect(screen.queryByRole("button", { name: /^Retry \(/u })).toBeNull();

		pagesAnswer.resolve();
		// No turn runs before it, so its own status line waits for Heph, as S1 words it. The line
		// shows once the turn stays queued past a second, and both the line and its status say it.
		await screen.findAllByText("Waiting for Heph to start…", {}, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(queryClient.isFetching()).toBe(0));
		expect(screen.getAllByText("A question that must stay visible")).toHaveLength(1);
		expect(fake.submissions.map(({ text }) => text)).toStrictEqual([
			"A question that must stay visible",
		]);
		expect(composer).toHaveProperty("value", "The next question is still a draft");
	} finally {
		postAnswer.resolve();
		pagesAnswer.resolve();
	}
});

it("copies and discards only one failed send without deleting stored messages or another draft", async () => {
	const user = userEvent.setup();
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "A message the server already stored" });
	serve(fake);
	const attempts: MentorTurnRequest[] = [];
	const deletes: string[] = [];
	server.use(
		// Records every message deletion the app asks for, then lets the fake answer it: a resolver that
		// returns nothing passes the request on to the next handler.
		http.delete("*/workspaces/acme/mentor/entries/:entryId", ({ request }) => {
			deletes.push(request.url);
		}),
		http.post<PathParams, MentorTurnRequest>(
			"*/workspaces/acme/mentor/turns",
			async ({ request }) => {
				attempts.push(await request.json());
				return HttpResponse.json(
					{ status: 503, detail: "The server could not store this message." },
					{ status: 503 },
				);
			},
		),
	);

	const { queryClient } = renderRouteAtWithRouter("/w/acme/mentor");
	const composer = await screen.findByRole("textbox", { name: "Message Heph" }, ROUTE_RENDER_WAIT);
	await user.type(composer, "The failed question to copy and discard{Enter}");
	await screen.findByText("Not sent. The server could not store this message.");
	await user.type(composer, "Another failed question to keep{Enter}");
	await waitFor(() =>
		expect(screen.getAllByText("Not sent. The server could not store this message.")).toHaveLength(
			2,
		),
	);
	await user.type(composer, "An unrelated draft still being written");

	const failed = screen
		.getByText("The failed question to copy and discard")
		.closest<HTMLElement>("[data-slot=message]");
	assert.isNotNull(failed);
	await user.click(within(failed).getByRole("button", { name: /^Copy message \(/u }));
	await expect(navigator.clipboard.readText()).resolves.toBe(
		"The failed question to copy and discard",
	);
	await screen.findByText("Message copied");
	await user.click(within(failed).getByRole("button", { name: /^Delete message \(/u }));

	await waitFor(() =>
		expect(screen.queryByText("The failed question to copy and discard")).toBeNull(),
	);
	await waitFor(() => expect(queryClient.isMutating()).toBe(0));
	expect(screen.queryByRole("alertdialog")).toBeNull();
	screen.getByText("A message the server already stored");
	screen.getByText("Another failed question to keep");
	expect(screen.getAllByText("Not sent. The server could not store this message.")).toHaveLength(1);
	expect(screen.getAllByRole("button", { name: /^Retry \(/u })).toHaveLength(1);
	expect(composer).toHaveProperty("value", "An unrelated draft still being written");
	expect(attempts.map(({ text }) => text)).toStrictEqual([
		"The failed question to copy and discard",
		"Another failed question to keep",
	]);
	expect(deletes).toStrictEqual([]);
	expect(fake.deletes).toStrictEqual([]);
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

	// One Stop for the running answer, beside Send; the status line in the log offers none.
	expect(within(screen.getByRole("log")).queryByRole("button", { name: /^Stop Heph/u })).toBeNull();
	await userEvent.click(screen.getByRole("button", { name: "Stop Heph" }));
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
	giveRoom(ROOMY);
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

it("keeps a deleted message's thread to read and delete in, but offers no reply the server would refuse", async () => {
	giveRoom(ROOMY);
	const fake = new MentorFake();
	// As the server keeps it: the message's words are gone, its thread stays.
	const parent = fake.add({ kind: "USER", text: "", deleted: true });
	fake.add({ kind: "HEPH", text: "Here is what I would change.", parentEntryId: parent.id });
	const own = fake.add({
		kind: "USER",
		text: "A reply I want gone too.",
		parentEntryId: parent.id,
	});
	fake.add({
		kind: "USER",
		text: "A reply Heph could not answer.",
		parentEntryId: parent.id,
		turnId: "a9000000-0000-4000-8000-000000000001",
		turnStatus: "FAILED",
		turnError: "Heph took too long to answer.",
	});
	serve(fake);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(panel).findByText("A reply I want gone too.");
	within(panel).getByText("Here is what I would change.");
	within(panel).getByText("This message was deleted.");
	within(panel).getByText("Heph took too long to answer.");
	// A deleted message's thread is not a thread the server does not know.
	expect(within(panel).queryByText("We could not find this thread")).toBeNull();
	// The server refuses every new reply to a deleted message, so nothing here asks for one.
	expect(within(panel).queryByRole("textbox", { name: "Reply in thread" })).toBeNull();
	expect(within(panel).queryByRole("button", { name: /^Try again \(/u })).toBeNull();
	expect(within(panel).queryByRole("button", { name: /^Reply in thread/u })).toBeNull();

	const deleteOwn = within(panel)
		.getAllByRole("button", { name: /^Delete message \(/u })
		.find(
			(button) => button.closest<HTMLElement>("[data-message-id]")?.dataset.messageId === own.id,
		);
	assert(deleteOwn);
	await userEvent.click(deleteOwn);
	await userEvent.click(await screen.findByRole("button", { name: "Delete message" }));
	await waitFor(() => expect(fake.deletes).toStrictEqual([own.id]));
	expect(fake.submissions).toStrictEqual([]);
});

it("says a thread is not found only when the server knows no such message", async () => {
	giveRoom(ROOMY);
	const fake = new MentorFake();
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor?thread=e9999999-0000-4000-8000-000000000000");
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(panel).findByText("We could not find this thread");
	expect(within(panel).queryByRole("textbox")).toBeNull();
});

it("says the thread is loading in its panel while the conversation beside it shows", async () => {
	giveRoom(ROOMY);
	const fake = new MentorFake();
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	const reply = fake.add({
		kind: "HEPH",
		text: "Share blockers earlier.",
		parentEntryId: parent.id,
	});
	serve(fake);
	const held = deferred();
	server.use(
		http.get(
			"*/workspaces/acme/mentor/entries/:entryId/thread",
			async () => {
				await held.promise;
				return HttpResponse.json({
					parent,
					conversationId: reply.conversationId,
					entries: [reply],
				});
			},
			{ once: true },
		),
	);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	try {
		const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
		await within(panel).findByText("Loading the thread…", {}, ROUTE_RENDER_WAIT);
		expect(within(panel).queryByText("Loading the conversation…")).toBeNull();

		held.resolve();
		await within(panel).findByText("Share blockers earlier.");
		expect(within(panel).queryByText("Loading the thread…")).toBeNull();
	} finally {
		held.resolve();
	}
});

it("shows a thread as a drawer until the page has measured room for it beside the conversation", async () => {
	const fake = new MentorFake();
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	fake.add({ kind: "HEPH", text: "Share blockers earlier.", parentEntryId: parent.id });
	serve(fake);

	// No measurement arrives, as before the browser lays the page out.
	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const drawer = await screen.findByRole("dialog", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(drawer).findByText("Share blockers earlier.");
	expect(screen.queryByRole("complementary", { name: "Thread" })).toBeNull();
});

it("needs a wider region for the thread's column when the reader's browser shows larger text", async () => {
	// The same 952px region that holds both at 16px: at 20px the 26rem column is 520px, and the
	// 432px left over is less than the conversation's 24rem, 480px.
	const resize = giveRoom(952, 20);
	const fake = new MentorFake();
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	fake.add({ kind: "HEPH", text: "Share blockers earlier.", parentEntryId: parent.id });
	serve(fake);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const drawer = await screen.findByRole("dialog", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(drawer).findByText("Share blockers earlier.");
	expect(screen.queryByRole("complementary", { name: "Thread" })).toBeNull();

	// 50rem at 20px is 1000px: a region that wide holds both again.
	resize(1000);
	await screen.findByRole("complementary", { name: "Thread" });
});

it("chooses the thread's column or drawer by the room the page measures, and keeps the reply being written", async () => {
	// 1000px windows: the open sidebar leaves this region 744px wide, the collapsed one 952px. A
	// 26rem column beside it would leave the conversation 328px, below its 24rem; 536px is enough.
	const resize = giveRoom(744);
	const fake = new MentorFake();
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	fake.add({ kind: "HEPH", text: "Share blockers earlier.", parentEntryId: parent.id });
	serve(fake);

	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const drawer = await screen.findByRole("dialog", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await userEvent.type(
		await within(drawer).findByRole("textbox", { name: "Reply in thread" }),
		"Half a reply",
	);
	expect(screen.queryByRole("complementary", { name: "Thread" })).toBeNull();

	resize(952);
	const column = await screen.findByRole("complementary", { name: "Thread" });
	expect(within(column).getByRole("textbox", { name: "Reply in thread" })).toHaveProperty(
		"value",
		"Half a reply",
	);
	await waitFor(() => expect(screen.queryByRole("dialog", { name: "Thread" })).toBeNull());

	resize(744);
	const again = await screen.findByRole("dialog", { name: "Thread" });
	await expect(
		within(again).findByRole("textbox", { name: "Reply in thread" }),
	).resolves.toHaveProperty("value", "Half a reply");
	expect(fake.submissions).toStrictEqual([]);
});

it("closes a thread drawer before the address drops the thread, and gives focus back to the summary that opened it", async () => {
	// A 1000px window with the sidebar open: no room for the column, so the thread is a drawer.
	giveRoom(744);
	const fake = new MentorFake();
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	fake.add({ kind: "HEPH", text: "Share blockers earlier.", parentEntryId: parent.id });
	serve(fake);

	const { router } = renderRouteAtWithRouter("/w/acme/mentor");
	const summary = await screen.findByRole("button", { name: /^1 reply/u }, ROUTE_RENDER_WAIT);
	await userEvent.click(summary);
	const drawer = await screen.findByRole("dialog", { name: "Thread" });
	await within(drawer).findByText("Share blockers earlier.");
	expect(router.state.location.href).toContain(`thread=${parent.id}`);

	await userEvent.click(within(drawer).getByRole("button", { name: "Close thread" }));
	await waitFor(() => {
		expect(screen.queryByRole("dialog", { name: "Thread" })).toBeNull();
		expect(router.state.location.href).not.toContain("thread=");
	});
	await waitFor(() => expect(document.activeElement).toBe(summary));
	expect(summary.getAttribute("aria-expanded")).toBe("false");
});

it("counts every reply above the open thread and on its message, from Slack and from the panel", async () => {
	giveRoom(ROOMY);
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

it("closes the live stream while the page is hidden, and reads back what landed meanwhile when it shows", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "Anything new?" });
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText("Anything new?", {}, ROUTE_RENDER_WAIT);
	const first = await liveStream();
	act(() => {
		first.open();
		first.emit("snapshot", fake.conversation());
	});

	const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
	try {
		act(() => {
			document.dispatchEvent(new Event("visibilitychange"));
		});
		// A background tab holds no connection of its own.
		await waitFor(() => expect(first.closed).toBe(true));
		expect(FakeEventSource.latest()).toBeUndefined();

		fake.add({ kind: "HEPH", text: "An answer that landed while the tab was hidden." });
		visibility.mockReturnValue("visible");
		act(() => {
			document.dispatchEvent(new Event("visibilitychange"));
		});
		const second = await liveStream();
		expect(second).not.toBe(first);
		act(() => {
			second.open();
			second.emit("snapshot", fake.conversation());
		});
		await screen.findByText("An answer that landed while the tab was hidden.");
	} finally {
		visibility.mockRestore();
	}
});

/** The open thread's reply field, once the thread shows. */
async function threadReplyField(): Promise<HTMLElement> {
	const panel = await screen.findByRole("complementary", { name: "Thread" }, ROUTE_RENDER_WAIT);
	return within(panel).findByRole("textbox", { name: "Reply in thread" });
}

it("starts each thread fresh, and gives a thread back the reply the developer was writing there", async () => {
	giveRoom(ROOMY);
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
	screen.getByRole("button", { name: "Stop Heph" });

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
			links: [],
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
			links: [],
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
	giveRoom(ROOMY);
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

it("says in a thread drawer over the conversation that live updates are paused, and clears it once one opens", async () => {
	const fake = new MentorFake();
	const parent = fake.add({ kind: "USER", text: "How did my week go?" });
	fake.add({ kind: "HEPH", text: "Share blockers earlier.", parentEntryId: parent.id });
	serve(fake);

	// No room is measured, so the thread is a drawer that covers the conversation and its notice.
	renderRouteAtWithRouter(`/w/acme/mentor?thread=${parent.id}`);
	const drawer = await screen.findByRole("dialog", { name: "Thread" }, ROUTE_RENDER_WAIT);
	await within(drawer).findByText("Share blockers earlier.");
	const stream = await liveStream();
	act(() => stream.open());
	act(() => stream.error());
	act(() => stream.error());
	await within(drawer).findByText("Live updates paused. Reconnecting…");
	act(() => stream.open());
	await waitFor(() =>
		expect(within(drawer).queryByText("Live updates paused. Reconnecting…")).toBeNull(),
	);
});

it("offers Retry in a linked thread when the conversation's state cannot be read, and shows the thread after it", async () => {
	giveRoom(ROOMY);
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

it("links a problem and feedback Heph showed to the developer's feedback on their work, as the server resolves it", async () => {
	const fake = new MentorFake();
	fake.add({ kind: "USER", text: "What should I fix in my pull request?" });
	fake.add({
		kind: "HEPH",
		text: "Two things stand out.",
		links: [
			{
				id: "d8000000-0000-4000-8000-000000000001",
				kind: "PROBLEM",
				text: "The description leaves out why the cache beats invalidating on write.",
				practiceSlug: "explain-decisions",
				itemId: 421,
			},
			{
				id: "f8000000-0000-4000-8000-000000000002",
				kind: "FEEDBACK",
				text: "Add one line on the trade-off before you merge.",
				itemId: 433,
			},
			{
				id: "d8000000-0000-4000-8000-000000000003",
				kind: "RESULT",
				text: "An earlier pull request explained its decision well.",
			},
		],
	});
	serve(fake);

	renderRouteAtWithRouter("/w/acme/mentor");
	await screen.findByText(
		"An earlier pull request explained its decision well.",
		{},
		ROUTE_RENDER_WAIT,
	);
	expect(
		screen
			.getAllByRole("link", { name: "Your feedback on this work" })
			.map((link) => link.getAttribute("href")),
	).toStrictEqual(["/w/acme/feedback/421", "/w/acme/feedback/433"]);
});

it("nests Heph's headings and a digest's sections under the page's headings without skipping a level", async () => {
	const fake = new MentorFake();
	const answer = fake.add({
		kind: "HEPH",
		text: "## Why\n\nThe reviewer could not see the reason.",
	});
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
					destination: "CONVERSATION",
					mentorEntryId: answer.id,
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
	giveRoom(ROOMY);
	const fake = new MentorFake();
	const digest = fake.add({
		kind: "DIGEST",
		surface: "SYSTEM",
		text: "Weekly digest for 5–11 October 2026. Read inputs/context/weekly_digest.json for it.",
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
					destination: "CONVERSATION",
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
	const link = await screen.findByRole("link", { name: /In conversation/u }, ROUTE_RENDER_WAIT);
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
