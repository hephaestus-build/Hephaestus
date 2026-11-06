import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";

afterEach(cleanup);

const context = { pagePath: "/w/$workspaceSlug/practice-profile", userAgent: "Mozilla/5.0 Test" };

function renderDialog(overrides: Partial<React.ComponentProps<typeof ProductFeedbackDialog>> = {}) {
	const props = {
		open: true,
		onOpenChange: vi.fn(),
		onRetrySharing: vi.fn(),
		reportsLink: <a href="/product-feedback">Your reports</a>,
		context,
		sharing: { status: "off" } as const,
		isSubmitting: false,
		onSubmit: vi.fn(async () => true),
		...overrides,
	};
	const view = render(<ProductFeedbackDialog {...props} />);
	return { ...view, props };
}

describe("product feedback dialog", () => {
	it("keeps a refused draft across close and reopen, and attaches details only once chosen", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog({ onSubmit: vi.fn(async () => false) });
		await user.type(screen.getByRole("textbox", { name: "Your feedback" }), "  An idea  ");
		await user.click(screen.getByRole("button", { name: "Send feedback" }));
		expect(props.onOpenChange).not.toHaveBeenCalledWith(false);

		rerender(<ProductFeedbackDialog {...props} open={false} />);
		rerender(<ProductFeedbackDialog {...props} open />);
		expect(screen.getByRole("textbox", { name: "Your feedback" })).toHaveProperty(
			"value",
			"  An idea  ",
		);
		await user.click(screen.getByText("Add page details (optional)"));
		await user.click(screen.getByRole("checkbox", { name: "Include page and browser details" }));
		await user.click(screen.getByRole("button", { name: "Send feedback" }));
		expect(props.onSubmit).toHaveBeenLastCalledWith({
			kind: "FEEDBACK",
			message: "An idea",
			pagePath: context.pagePath,
			userAgent: context.userAgent,
		});
	});

	it("clears the message only after the send was accepted", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		await user.type(screen.getByRole("textbox", { name: "Your feedback" }), "An idea");
		await user.click(screen.getByRole("button", { name: "Send feedback" }));
		await waitFor(() => expect(props.onSubmit).toHaveBeenCalledOnce());
		await waitFor(() =>
			expect(screen.getByRole("textbox", { name: "Your feedback" })).toHaveProperty("value", ""),
		);
		rerender(<ProductFeedbackDialog {...props} open={false} />);
		rerender(<ProductFeedbackDialog {...props} open />);
		expect(screen.getByRole("textbox", { name: "Your feedback" })).toHaveProperty("value", "");
	});

	it("does not change the context choice while sending", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		await user.click(screen.getByText("Add page details (optional)"));
		const checkbox = screen.getByRole("checkbox", {
			name: "Include page and browser details",
		});
		await user.click(checkbox);
		rerender(<ProductFeedbackDialog {...props} isSubmitting />);
		await user.click(checkbox);
		expect(checkbox.getAttribute("aria-checked")).toBe("true");
	});
	it("explains an empty submission, focuses the message, and clears the error while writing", async () => {
		const user = userEvent.setup();
		renderDialog();
		await user.click(screen.getByRole("button", { name: "Send feedback" }));
		screen.getByText("Write your feedback before sending.");
		const field = screen.getByRole("textbox", { name: "Your feedback" });
		expect(document.activeElement).toBe(field);
		await user.type(field, "Let me pin a practice.");
		expect(screen.queryByText("Write your feedback before sending.")).toBeNull();
	});

	it("keeps the original page details with a draft after the page changes", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		await user.type(screen.getByRole("textbox", { name: "Your feedback" }), "This page jumps.");
		rerender(
			<ProductFeedbackDialog {...props} context={{ ...context, pagePath: "/product-feedback" }} />,
		);
		await user.click(screen.getByText("Add page details (optional)"));
		screen.getByText(context.pagePath);
		expect(screen.queryByText("/product-feedback")).toBeNull();
	});

	it("uses the current page for a new draft after the previous text was cleared", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		const message = screen.getByRole("textbox", { name: "Your feedback" });
		await user.type(message, "An abandoned draft");
		await user.clear(message);
		rerender(
			<ProductFeedbackDialog {...props} context={{ ...context, pagePath: "/product-feedback" }} />,
		);
		await user.type(message, "A new report");
		await user.click(screen.getByText("Add page details (optional)"));
		screen.getByText("/product-feedback");
		expect(screen.queryByText(context.pagePath)).toBeNull();
	});
});
