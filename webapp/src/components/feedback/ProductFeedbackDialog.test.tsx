import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";

afterEach(cleanup);

describe("product feedback form", () => {
	it("includes page context only after an explicit choice and retains failed drafts", async () => {
		const onSubmit = vi.fn(() => Promise.resolve(false));
		const user = userEvent.setup();
		render(
			<ProductFeedbackDialog
				isSubmitting={false}
				pagePath="/w/acme/practices"
				onSubmit={onSubmit}
				error="Couldn't send. Your draft is still here."
			/>,
		);
		await user.click(screen.getByRole("button", { name: "Send product feedback" }));
		await user.type(screen.getByRole("textbox", { name: "Message" }), "  An idea  ");
		await user.click(screen.getByRole("button", { name: "Send" }));
		expect(onSubmit).toHaveBeenLastCalledWith("FEEDBACK", "An idea", false);
		expect(screen.getByRole("alert").textContent).toContain("Your draft is still here");
		await user.keyboard("{Escape}");
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		await user.click(screen.getByRole("button", { name: "Send product feedback" }));
		expect(await screen.findByRole("textbox", { name: "Message" })).toHaveProperty(
			"value",
			"  An idea  ",
		);
		await user.click(screen.getByRole("checkbox", { name: "Include current page path" }));
		await user.click(screen.getByRole("button", { name: "Send" }));
		expect(onSubmit).toHaveBeenLastCalledWith("FEEDBACK", "An idea", true);
	});

	it("closes only after successful delivery and clears the submitted text", async () => {
		const onSubmit = vi.fn(() => Promise.resolve(true));
		const user = userEvent.setup();
		render(<ProductFeedbackDialog isSubmitting={false} onSubmit={onSubmit} />);
		await user.click(screen.getByRole("button", { name: "Send product feedback" }));
		await user.type(screen.getByRole("textbox", { name: "Message" }), "An idea");
		await user.click(screen.getByRole("button", { name: "Send" }));
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		await user.click(screen.getByRole("button", { name: "Send product feedback" }));
		expect((await screen.findByRole("textbox", { name: "Message" })).textContent).toBe("");
	});
	it("does not change page-context consent while sending", async () => {
		const props = {
			isSubmitting: false,
			pagePath: "/w/acme/practices",
			onSubmit: vi.fn(() => Promise.resolve(true)),
		};
		const user = userEvent.setup();
		const { rerender } = render(<ProductFeedbackDialog {...props} />);
		await user.click(screen.getByRole("button", { name: "Send product feedback" }));
		const checkbox = screen.getByRole("checkbox", { name: "Include current page path" });
		await user.click(checkbox);
		rerender(<ProductFeedbackDialog {...props} isSubmitting />);
		await user.click(checkbox);
		expect(checkbox.getAttribute("aria-checked")).toBe("true");
	});
});
