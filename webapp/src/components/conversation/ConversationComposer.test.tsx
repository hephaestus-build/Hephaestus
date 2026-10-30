import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";

import { ConversationComposer } from "./ConversationComposer";

it.each([
	{
		pointer: "fine",
		enterKeyHint: "send",
		sentOnEnter: ["First line"],
		value: "",
		sentOnSend: "Second line",
	},
	{
		pointer: "coarse",
		enterKeyHint: "enter",
		sentOnEnter: [],
		value: "First line\n",
		sentOnSend: "First line\nSecond line",
	},
])(
	"with a $pointer pointer, the keyboard's key says $enterKeyHint and Enter sends $sentOnEnter",
	async ({ pointer, enterKeyHint, sentOnEnter, value, sentOnSend }) => {
		const media = window.matchMedia.bind(window);
		using _pointerMedia = vi
			.spyOn(window, "matchMedia")
			.mockImplementation((query) =>
				Object.assign(media(query), { matches: query === `(pointer: ${pointer})` }),
			);
		const user = userEvent.setup();
		const onSend = vi.fn<(text: string) => void>();
		render(
			<ConversationComposer
				label="Message Hephaestus"
				placeholder="Message Hephaestus"
				onSend={onSend}
			/>,
		);
		const field = screen.getByRole("textbox", { name: "Message Hephaestus" });
		expect(field.getAttribute("enterkeyhint")).toBe(enterKeyHint);

		await user.type(field, "First line{Enter}");
		expect(onSend.mock.calls.map(([text]) => text)).toStrictEqual(sentOnEnter);
		expect(field).toHaveProperty("value", value);

		// Send sends on any pointer.
		await user.type(field, "Second line");
		await user.click(screen.getByRole("button", { name: "Send message" }));
		expect(onSend).toHaveBeenLastCalledWith(sentOnSend);
	},
);

it.each(["Meta", "Control"])(
	"with a coarse pointer, %s with Enter still sends, for a tablet with a hardware keyboard",
	async (modifier) => {
		const media = window.matchMedia.bind(window);
		using _pointerMedia = vi
			.spyOn(window, "matchMedia")
			.mockImplementation((query) =>
				Object.assign(media(query), { matches: query === "(pointer: coarse)" }),
			);
		const user = userEvent.setup();
		const onSend = vi.fn<(text: string) => void>();
		render(
			<ConversationComposer
				label="Message Hephaestus"
				placeholder="Message Hephaestus"
				onSend={onSend}
			/>,
		);
		const field = screen.getByRole("textbox", { name: "Message Hephaestus" });

		await user.type(field, `From the keyboard{${modifier}>}{Enter}{/${modifier}}`);
		expect(onSend).toHaveBeenCalledWith("From the keyboard");
		expect(field).toHaveProperty("value", "");
	},
);
