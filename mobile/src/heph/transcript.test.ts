import assert from "node:assert/strict";
import { describe, expect, it } from "vitest";

import {
	composerState,
	type HephMessage,
	interruptionOf,
	questionToResend,
	recordedInterruption,
	lastReplyInterrupted,
	parseTranscript,
	textOf,
	transcriptState,
} from "./transcript";

const USER = "7b6f3f5e-8a4d-4b36-9e1f-0a9c4f6e2d11";
const HEPH = "2c1d9e7a-5b3f-4e8d-a6c2-1f0e9d8c7b6a";

describe("parseTranscript", () => {
	it("keeps part kinds this build does not render", () => {
		const parsed = parseTranscript([
			{ id: USER, role: "user", parts: [{ type: "text", text: "Why?" }] },
			{
				id: HEPH,
				role: "assistant",
				parts: [
					{ type: "data-usage", data: { tokens: 3 } },
					{ type: "text", text: "Because." },
				],
			},
		]);

		expect(parsed).toHaveLength(2);
		expect(parsed?.[1]?.parts[0]?.type).toBe("data-usage");
	});

	it("refuses a text part without its text", () => {
		expect(
			parseTranscript([{ id: USER, role: "user", parts: [{ type: "text" }] }]),
		).toBeUndefined();
	});

	it("refuses anything that is not a list of messages", () => {
		expect(parseTranscript({ messages: [] })).toBeUndefined();
		expect(parseTranscript([{ id: "not-a-uuid", role: "user", parts: [] }])).toBeUndefined();
	});
});

describe("lastReplyInterrupted", () => {
	it("recognises a reply the server recorded as cut off", () => {
		const messages = parseTranscript([
			{ id: USER, role: "user", parts: [{ type: "text", text: "Hi" }] },
			{
				id: HEPH,
				role: "assistant",
				parts: [{ type: "text", text: "Hel" }],
				metadata: { error: "client disconnected" },
			},
		]);

		assert.ok(messages);
		expect(lastReplyInterrupted(messages)).toBe(true);
	});

	it("does not flag a finished reply", () => {
		const messages = parseTranscript([
			{
				id: HEPH,
				role: "assistant",
				parts: [{ type: "text", text: "Done." }],
				metadata: { model: "m" },
			},
		]);

		assert.ok(messages);
		expect(lastReplyInterrupted(messages)).toBe(false);
	});
});

describe("textOf", () => {
	it("joins the text parts and skips the rest", () => {
		const messages = parseTranscript([
			{
				id: HEPH,
				role: "assistant",
				parts: [{ type: "text", text: "A" }, { type: "step-start" }, { type: "text", text: "B" }],
			},
		]);
		assert.ok(messages);
		const [message] = messages;
		assert.ok(message);

		expect(textOf(message)).toBe("A\n\nB");
	});
});

describe("transcriptState", () => {
	const existing = { isNew: false, shown: 0, fetch: "success" as const, readable: true };

	it("shows an unreadable history as a failure to retry, never as an empty conversation", () => {
		expect(transcriptState({ ...existing, readable: false })).toBe("unreadable");
	});

	it("opens an existing conversation whose readable history is empty", () => {
		expect(transcriptState(existing)).toBe("ready");
	});

	it("starts a new conversation at once, with nothing to read", () => {
		expect(transcriptState({ ...existing, isNew: true, fetch: "pending", readable: false })).toBe(
			"ready",
		);
	});

	it("says loading and failed while nothing is on screen", () => {
		expect(transcriptState({ ...existing, fetch: "pending" })).toBe("loading");
		expect(transcriptState({ ...existing, fetch: "error" })).toBe("failed");
	});

	it("keeps history already on screen when a later read cannot be understood", () => {
		expect(transcriptState({ ...existing, shown: 4, readable: false })).toBe("ready");
	});
});

describe("composerState", () => {
	it("takes a message only in a ready conversation with Heph available", () => {
		expect(composerState("available", "ready")).toStrictEqual({
			enabled: true,
			placeholder: "Message Heph",
		});
		expect(composerState("checking", "ready").enabled).toBe(false);
		expect(composerState("available", "unreadable").enabled).toBe(false);
	});

	it("never says Heph is unavailable while access or the conversation is still loading", () => {
		for (const state of [
			composerState("checking", "ready"),
			composerState("available", "loading"),
		]) {
			expect(state).toStrictEqual({ enabled: false, placeholder: "Loading…" });
		}
	});

	it("names why nothing can be sent", () => {
		expect(composerState("no-access", "ready").placeholder).toBe("Heph is not available");
		expect(composerState("unreachable", "ready").placeholder).toBe("Could not reach Heph");
		expect(composerState("available", "failed").placeholder).toBe(
			"Nothing can be sent until it loads",
		);
	});
});

function turn(id: string, role: "user" | "assistant"): HephMessage {
	return { id, role, parts: [{ type: "text", text: id }] };
}

describe("questionToResend", () => {
	it("sends the person's last question again, not the reply after it or an earlier one", () => {
		expect(
			questionToResend([
				turn("q1", "user"),
				turn("a1", "assistant"),
				turn("q2", "user"),
				turn("a2", "assistant"),
			]),
		).toBe("q2");
	});

	it("offers nothing to send before the person has said anything, or when they said nothing", () => {
		expect(questionToResend([])).toBeUndefined();
		expect(questionToResend([turn("a0", "assistant")])).toBeUndefined();
		expect(
			questionToResend([{ id: "q", role: "user", parts: [{ type: "text", text: "  " }] }]),
		).toBeUndefined();
	});
});

function reply(metadata?: HephMessage["metadata"], text = ""): HephMessage {
	return {
		id: "reply",
		role: "assistant",
		parts: text === "" ? [] : [{ type: "text", text }],
		...(metadata === undefined ? {} : { metadata }),
	};
}

describe("interruptionOf", () => {
	const asked = turn("q", "user");
	const settled = { busy: false, failed: false, ended: undefined };
	const stoppedHere = { aborted: true };

	it("says nothing while a reply is still arriving", () => {
		expect(
			interruptionOf({ ...settled, busy: true, ended: stoppedHere, messages: [asked] }),
		).toBeUndefined();
	});

	it("says the request failed when it did", () => {
		expect(interruptionOf({ ...settled, failed: true, messages: [asked, reply()] })).toBe("failed");
	});

	it("shows a stop at once, before Heph wrote a word or the server recorded anything", () => {
		expect(interruptionOf({ ...settled, ended: stoppedHere, messages: [asked] })).toBe("stopped");
		expect(interruptionOf({ ...settled, ended: stoppedHere, messages: [asked, reply()] })).toBe(
			"stopped",
		);
	});

	it("keeps a stop when the stored copy read back straight after is still in flight", () => {
		const inFlight = reply({ status: "in_flight" }, "Part");
		expect(interruptionOf({ ...settled, ended: stoppedHere, messages: [asked, inFlight] })).toBe(
			"stopped",
		);
	});

	it("keeps a stop when the server stored the turn as completed but ended in an error", () => {
		const raced = reply({ status: "completed", finishReason: "error" });
		expect(interruptionOf({ ...settled, ended: stoppedHere, messages: [asked, raced] })).toBe(
			"stopped",
		);
	});

	it("lets a reply the server holds as properly finished override a stop made here", () => {
		const whole = reply({ status: "completed", finishReason: "stop" }, "All of it");
		expect(
			interruptionOf({ ...settled, ended: stoppedHere, messages: [asked, whole] }),
		).toBeUndefined();
	});

	it("reads a turn the server cut off, whoever stopped it", () => {
		expect(
			interruptionOf({ ...settled, messages: [asked, reply({ status: "interrupted" }, "Half")] }),
		).toBe("stopped");
		expect(
			interruptionOf({ ...settled, messages: [asked, reply({ error: "disconnected" }, "Half")] }),
		).toBe("stopped");
	});

	it("fails a reply that ended in an error as it finishes here, without calling it a stop", () => {
		expect(
			interruptionOf({
				...settled,
				ended: { aborted: false, finishReason: "error" },
				messages: [asked, reply()],
			}),
		).toBe("failed");
	});

	it("fails a stored reply that ended in an error when the conversation is opened again", () => {
		const stored = reply({ status: "completed", finishReason: "error" });
		expect(interruptionOf({ ...settled, messages: [asked, stored] })).toBe("failed");
	});

	it("says a reply cut short by length or by the content filter may be incomplete", () => {
		for (const finishReason of ["length", "content-filter"]) {
			expect(
				interruptionOf({
					...settled,
					messages: [asked, reply({ status: "completed", finishReason }, "Most")],
				}),
			).toBe("cut-off");
			expect(
				interruptionOf({
					...settled,
					ended: { aborted: false, finishReason },
					messages: [asked, reply()],
				}),
			).toBe("cut-off");
		}
	});

	it("does not invent a problem for a reply that finished", () => {
		for (const finishReason of [undefined, "stop", "tool-calls", "other"]) {
			const ended = { aborted: false, finishReason };
			expect(
				interruptionOf({ ...settled, ended, messages: [asked, reply(undefined, "Done")] }),
			).toBeUndefined();
		}
	});

	it("prefers the server's record of how the turn ended over what streamed here", () => {
		const stored = reply({ status: "completed", finishReason: "stop" }, "Done");
		expect(
			interruptionOf({
				...settled,
				ended: { aborted: false, finishReason: "error" },
				messages: [asked, stored],
			}),
		).toBeUndefined();
	});
});

describe("recordedInterruption", () => {
	it("keeps an earlier reply's recorded ending once a new turn follows it", () => {
		expect(recordedInterruption({ status: "completed", finishReason: "error" })).toBe("failed");
		expect(recordedInterruption({ status: "interrupted" })).toBe("stopped");
		expect(recordedInterruption({ error: "disconnected" })).toBe("stopped");
		expect(recordedInterruption({ status: "completed", finishReason: "length" })).toBe("cut-off");
		expect(recordedInterruption({ status: "completed", finishReason: "content-filter" })).toBe(
			"cut-off",
		);
	});

	it("says nothing about a reply that finished or has no record", () => {
		expect(recordedInterruption(undefined)).toBeUndefined();
		expect(recordedInterruption({ status: "completed", finishReason: "stop" })).toBeUndefined();
		expect(recordedInterruption({ status: "in_flight" })).toBeUndefined();
	});
});
