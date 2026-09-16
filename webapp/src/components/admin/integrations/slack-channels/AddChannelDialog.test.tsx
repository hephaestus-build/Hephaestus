import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { assert, describe, expect, it, vi } from "vitest";

import { AddChannelDialog, type AddChannelDialogProps } from "./AddChannelDialog";

describe("AddChannelDialog — form submit", () => {
	it("submits on Enter once the pasted reference is valid", async () => {
		const onSubmit = vi.fn().mockResolvedValue(undefined);
		render(
			<AddChannelDialog
				open
				onOpenChange={vi.fn<() => void>()}
				candidates={[]}
				onSubmit={onSubmit}
			/>,
		);

		const input = screen.getByLabelText(/paste a channel link or id/iu);
		fireEvent.change(input, { target: { value: "C0974LJBPBK" } });
		const form = input.closest("form");
		assert(form);
		fireEvent.submit(form);

		await waitFor(() =>
			expect(onSubmit).toHaveBeenCalledWith({
				slackChannelId: "C0974LJBPBK",
				channelName: undefined,
			}),
		);
	});

	it("does not trim the pasted-reference field on every keystroke (no cursor jump)", () => {
		render(
			<AddChannelDialog
				open
				onOpenChange={vi.fn<() => void>()}
				candidates={[]}
				onSubmit={vi.fn()}
			/>,
		);

		const input = screen.getByLabelText<HTMLInputElement>(/paste a channel link or id/iu);
		fireEvent.change(input, { target: { value: "  C0974LJBPBK  " } });

		// The raw value (including interior/leading/trailing whitespace) is kept in state; only
		// the parser trims. If this trimmed on every keystroke, typing in the middle of a pasted
		// value would keep snapping the cursor to the end.
		expect(input.value).toBe("  C0974LJBPBK  ");
	});

	it("resets the form fields on close instead of relying on a remount", () => {
		const onOpenChange = vi.fn<AddChannelDialogProps["onOpenChange"]>();
		render(
			<AddChannelDialog open onOpenChange={onOpenChange} candidates={[]} onSubmit={vi.fn()} />,
		);

		const input = screen.getByLabelText<HTMLInputElement>(/paste a channel link or id/iu);
		fireEvent.change(input, { target: { value: "C0974LJBPBK" } });
		expect(input.value).toBe("C0974LJBPBK");

		// Cancel routes through onOpenChange(false), which resets the fields synchronously on the
		// same instance (no `key`-driven remount).
		fireEvent.click(screen.getByRole("button", { name: /^cancel$/iu }));

		expect(onOpenChange).toHaveBeenCalledWith(false);
		expect(screen.getByLabelText<HTMLInputElement>(/paste a channel link or id/iu).value).toBe("");
	});
});
