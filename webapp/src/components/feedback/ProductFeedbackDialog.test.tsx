import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";

afterEach(cleanup);

const context = { pagePath: "/w/acme/practices", userAgent: "Mozilla/5.0 Test" };

function renderDialog(overrides: Partial<React.ComponentProps<typeof ProductFeedbackDialog>> = {}) {
	const props = {
		open: true,
		onOpenChange: vi.fn(),
		kind: "FEEDBACK" as const,
		onKindChange: vi.fn(),
		context,
		isSubmitting: false,
		onSubmit: vi.fn(() => Promise.resolve(true)),
		...overrides,
	};
	const view = render(<ProductFeedbackDialog {...props} />);
	return { ...view, props };
}

describe("product feedback dialog", () => {
	it("attaches page and browser details only after an explicit choice and keeps a failed draft", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog({
			onSubmit: vi.fn(() => Promise.resolve(false)),
			error: "Couldn't send. Your draft is still here.",
		});
		await user.type(screen.getByRole("textbox", { name: "Message" }), "  An idea  ");
		await user.click(screen.getByRole("button", { name: "Send" }));
		expect(props.onSubmit).toHaveBeenLastCalledWith({
			kind: "FEEDBACK",
			message: "An idea",
			pagePath: undefined,
			userAgent: undefined,
		});
		expect(screen.getByRole("alert").textContent).toContain("Your draft is still here");
		expect(props.onOpenChange).not.toHaveBeenCalledWith(false);

		rerender(<ProductFeedbackDialog {...props} open={false} />);
		rerender(<ProductFeedbackDialog {...props} open />);
		expect(screen.getByRole("textbox", { name: "Message" })).toHaveProperty("value", "  An idea  ");
		await user.click(screen.getByRole("checkbox", { name: "Include page and browser details" }));
		await user.click(screen.getByRole("button", { name: "Send" }));
		expect(props.onSubmit).toHaveBeenLastCalledWith({
			kind: "FEEDBACK",
			message: "An idea",
			pagePath: context.pagePath,
			userAgent: context.userAgent,
		});
	});

	it("starts a bug report with the details ticked and lets the sender untick them", async () => {
		const user = userEvent.setup();
		const { props } = renderDialog({ kind: "BUG" });
		const checkbox = screen.getByRole("checkbox", { name: "Include page and browser details" });
		expect(checkbox.getAttribute("aria-checked")).toBe("true");
		await user.click(checkbox);
		await user.type(screen.getByRole("textbox", { name: "Message" }), "It broke");
		await user.click(screen.getByRole("button", { name: "Send" }));
		expect(props.onSubmit).toHaveBeenLastCalledWith({
			kind: "BUG",
			message: "It broke",
			pagePath: undefined,
			userAgent: undefined,
		});
	});

	it("closes and clears the message only after the send was accepted", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		await user.type(screen.getByRole("textbox", { name: "Message" }), "An idea");
		await user.click(screen.getByRole("button", { name: "Send" }));
		await waitFor(() => expect(props.onOpenChange).toHaveBeenCalledWith(false));
		rerender(<ProductFeedbackDialog {...props} open={false} />);
		rerender(<ProductFeedbackDialog {...props} open />);
		expect(screen.getByRole("textbox", { name: "Message" })).toHaveProperty("value", "");
	});

	it("does not change the context choice while sending", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		const checkbox = screen.getByRole("checkbox", { name: "Include page and browser details" });
		await user.click(checkbox);
		rerender(<ProductFeedbackDialog {...props} isSubmitting />);
		await user.click(checkbox);
		expect(checkbox.getAttribute("aria-checked")).toBe("true");
	});
});
