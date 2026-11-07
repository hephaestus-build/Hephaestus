import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";

afterEach(cleanup);

const pagePath = "/w/$workspaceSlug/practice-profile";

function renderDialog(overrides: Partial<React.ComponentProps<typeof ProductFeedbackDialog>> = {}) {
	const props = {
		open: true,
		onOpenChange: vi.fn(),
		reportsLink: <a href="/product-feedback">Your reports</a>,
		pagePath,
		sharing: { status: "off" } as const,
		isSubmitting: false,
		onSubmit: vi.fn(async () => true),
		...overrides,
	};
	const view = render(<ProductFeedbackDialog {...props} />);
	return { ...view, props };
}

const message = () => screen.getByRole("textbox", { name: "Your feedback" });
const send = () => screen.getByRole("button", { name: "Send" });
const publicBox = () => screen.getByRole("checkbox", { name: "Post as a public GitHub issue" });

describe("product feedback dialog", () => {
	it("keeps a refused draft across close and reopen", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog({ onSubmit: vi.fn(async () => false) });
		await user.type(message(), "  An idea  ");
		await user.click(send());
		expect(props.onOpenChange).not.toHaveBeenCalledWith(false);

		rerender(<ProductFeedbackDialog {...props} open={false} />);
		rerender(<ProductFeedbackDialog {...props} open />);
		expect(message()).toHaveProperty("value", "  An idea  ");
	});

	it("clears the message only after the send was accepted", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		await user.type(message(), "An idea");
		await user.click(send());
		await waitFor(() => expect(props.onSubmit).toHaveBeenCalledOnce());
		await waitFor(() => expect(message()).toHaveProperty("value", ""));
		rerender(<ProductFeedbackDialog {...props} open={false} />);
		rerender(<ProductFeedbackDialog {...props} open />);
		expect(message()).toHaveProperty("value", "");
	});

	it("explains an empty submission, focuses the message, and clears the error while writing", async () => {
		const user = userEvent.setup();
		renderDialog();
		await user.click(send());
		screen.getByText("Write your feedback before sending.");
		expect(document.activeElement).toBe(message());
		await user.type(message(), "Let me pin a practice.");
		expect(screen.queryByText("Write your feedback before sending.")).toBeNull();
	});

	it("sends the page and a private report on a first report, with nothing else to do", async () => {
		const user = userEvent.setup();
		const { props } = renderDialog({ sharing: { status: "public", remembered: false } });
		await user.type(message(), "An idea");
		await user.click(send());
		expect(props.onSubmit).toHaveBeenCalledWith({
			kind: "FEEDBACK",
			message: "An idea",
			pagePath,
			share: false,
		});
	});

	it("makes a report public only when the box is ticked", async () => {
		const user = userEvent.setup();
		const { props } = renderDialog({ sharing: { status: "public", remembered: false } });
		await user.type(message(), "An idea");
		await user.click(publicBox());
		await user.click(send());
		expect(props.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ share: true }));
	});

	it("starts from an earlier tick, and unticking applies to this report", async () => {
		const user = userEvent.setup();
		const { props } = renderDialog({ sharing: { status: "public", remembered: true } });
		expect(publicBox().getAttribute("aria-checked")).toBe("true");
		await user.type(message(), "An idea");
		await user.click(publicBox());
		await user.click(send());
		expect(props.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ share: false }));
	});

	it.each([
		["loading", { status: "loading" }],
		["failed", { status: "error" }],
	] as const)(
		"keeps Send open and the report private while sharing is %s",
		async (_name, sharing) => {
			const user = userEvent.setup();
			const { props } = renderDialog({ sharing });
			await user.type(message(), "An idea");
			await user.click(send());
			expect(props.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ share: false }));
		},
	);

	it("keeps the original page with a draft after the page changes", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		await user.type(message(), "This page jumps.");
		rerender(<ProductFeedbackDialog {...props} pagePath="/product-feedback" />);
		await user.click(send());
		expect(props.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ pagePath }));
	});

	it("uses the current page for a new draft after the previous text was cleared", async () => {
		const user = userEvent.setup();
		const { props, rerender } = renderDialog();
		await user.type(message(), "An abandoned draft");
		await user.clear(message());
		rerender(<ProductFeedbackDialog {...props} pagePath="/product-feedback" />);
		await user.type(message(), "A new report");
		await user.click(send());
		expect(props.onSubmit).toHaveBeenCalledWith(
			expect.objectContaining({ pagePath: "/product-feedback" }),
		);
	});
});
