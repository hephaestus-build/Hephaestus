import { describe, expect, it } from "vitest";

import { createHydration, type Hydration, storedToApply } from "./hydration";

interface Message {
	id: string;
	role: "user" | "assistant";
}

const user = (id: string): Message => ({ id, role: "user" });
const reply = (id: string): Message => ({ id, role: "assistant" });

/**
 * One conversation screen as React runs it: the hydration effect re-runs whenever the messages
 * change, and the chat hook hands back a fresh copy of the array on every update.
 */
function screen() {
	const hydration: Hydration = createHydration();
	let messages: Message[] = [];
	let updates = 0;
	const setMessages = (next: Message[]) => {
		messages = [...next];
		updates += 1;
	};
	const settle = (snapshot: {
		stored: Message[] | undefined;
		fetchedAt: number;
		busy?: boolean;
	}) => {
		// Bounded, so a rule that applies the same snapshot on every render fails instead of hanging.
		for (let run = 0; run < 20; run += 1) {
			const next = storedToApply(hydration, {
				busy: snapshot.busy ?? false,
				local: messages,
				...snapshot,
			});
			if (next === undefined) {
				return;
			}
			setMessages(next);
		}
		throw new Error("the hydration effect never settled");
	};
	return {
		settle,
		send: (message: Message, at: number) => {
			hydration.sentAt = at;
			setMessages([...messages, message]);
		},
		stream: (message: Message) => setMessages([...messages, message]),
		get messages() {
			return messages;
		},
		get updates() {
			return updates;
		},
	};
}

describe("conversation hydration", () => {
	it("puts a stored conversation on screen once, however often the effect runs again", () => {
		const view = screen();
		const stored = [user("m1"), reply("r1")];

		view.settle({ stored, fetchedAt: 100 });
		view.settle({ stored, fetchedAt: 100 });

		expect(view.messages).toStrictEqual(stored);
		expect(view.updates).toBe(1);
	});

	it("keeps the reply that just finished when the cached copy is older", () => {
		const view = screen();
		view.settle({ stored: [user("m1"), reply("r1")], fetchedAt: 100 });
		view.send(user("m2"), 200);
		view.stream(reply("r2"));

		view.settle({ stored: [user("m1"), reply("r1")], fetchedAt: 100 });

		expect(view.messages.map((message) => message.id)).toStrictEqual(["m1", "r1", "m2", "r2"]);
	});

	it("takes the server's copy read after the reply finished", () => {
		const view = screen();
		view.send(user("m1"), 100);
		view.stream(reply("r1-partial"));

		const stored = [user("m1"), reply("r1")];
		view.settle({ stored, fetchedAt: 300 });

		expect(view.messages).toStrictEqual(stored);
	});

	it("ignores a copy read while the reply was still streaming, even after it ends", () => {
		const view = screen();
		view.send(user("m1"), 100);
		view.settle({ stored: [user("m1")], fetchedAt: 150, busy: true });
		view.stream(reply("r1"));

		view.settle({ stored: [user("m1")], fetchedAt: 150 });

		expect(view.messages.map((message) => message.id)).toStrictEqual(["m1", "r1"]);
	});

	it("keeps a message that failed to send so it can be sent again", () => {
		const view = screen();
		view.settle({ stored: [user("m1"), reply("r1")], fetchedAt: 100 });
		view.send(user("m2"), 200);

		view.settle({ stored: [user("m1"), reply("r1")], fetchedAt: 300 });

		expect(view.messages.map((message) => message.id)).toStrictEqual(["m1", "r1", "m2"]);
	});

	it("takes a turn the server recorded while the app was away", () => {
		const view = screen();
		view.send(user("m1"), 100);
		view.stream(reply("r1-cut-off"));

		const stored = [user("m1"), reply("r1-interrupted")];
		view.settle({ stored, fetchedAt: 500 });

		expect(view.messages).toStrictEqual(stored);
	});
});
