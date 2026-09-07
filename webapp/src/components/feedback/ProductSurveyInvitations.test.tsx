import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { productSurvey } from "./product-survey-fixtures";
import { ProductSurveyInvitations } from "./ProductSurveyInvitations";

afterEach(cleanup);

function renderInvitations() {
	const onSubmit = vi.fn(() => Promise.resolve(false));
	const onDismiss = vi.fn(() => Promise.resolve(true));
	const props = {
		surveys: [productSurvey],
		isLoading: false,
		loadError: false,
		isPending: false,
		onRetry: vi.fn(),
		onSelect: vi.fn(),
		onSubmit,
		onDismiss,
	};
	const { rerender } = render(<ProductSurveyInvitations {...props} />);
	return {
		onSubmit,
		onDismiss,
		user: userEvent.setup(),
		setPending: () => rerender(<ProductSurveyInvitations {...props} isPending />),
	};
}

describe("survey invitations", () => {
	it("never opens automatically and restores the draft after Escape and navigation back to the list", async () => {
		const { user, onDismiss } = renderInvitations();
		expect(screen.queryByRole("dialog")).toBeNull();
		await user.click(screen.getByRole("button", { name: "Product surveys (1 available)" }));
		await user.click(await screen.findByRole("button", { name: "Take survey" }));
		await user.type(screen.getByRole("textbox", { name: /What should improve/ }), "Keep my draft");
		await user.keyboard("{Escape}");
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		await user.click(screen.getByRole("button", { name: "Product surveys (1 available)" }));
		expect(await screen.findByRole("textbox", { name: /What should improve/ })).toHaveProperty(
			"value",
			"Keep my draft",
		);
		await user.click(screen.getByRole("button", { name: "Back to surveys" }));
		expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Product surveys" }));
		await user.click(screen.getByRole("button", { name: "Take survey" }));
		expect(screen.getByRole("textbox", { name: /What should improve/ })).toHaveProperty(
			"value",
			"Keep my draft",
		);
		expect(onDismiss).not.toHaveBeenCalled();
	});

	it("allows a skipped optional answer and retains input after submission failure", async () => {
		const { user, onSubmit } = renderInvitations();
		await user.click(screen.getByRole("button", { name: "Product surveys (1 available)" }));
		await user.click(await screen.findByRole("button", { name: "Take survey" }));
		expect(screen.getByRole("button", { name: "Submit response" }).hasAttribute("disabled")).toBe(
			true,
		);
		await user.click(screen.getByRole("radio", { name: "4" }));
		await user.click(screen.getByRole("button", { name: "Submit response" }));
		expect(onSubmit).toHaveBeenCalledWith(productSurvey.id, { useful: "4" });
		expect(screen.getByRole("radio", { name: "4" }).getAttribute("aria-checked")).toBe("true");
	});
	it("keeps answers unchanged while a response is being submitted", async () => {
		const { user, setPending } = renderInvitations();
		await user.click(screen.getByRole("button", { name: "Product surveys (1 available)" }));
		await user.click(await screen.findByRole("button", { name: "Take survey" }));
		await user.click(screen.getByRole("radio", { name: "4" }));
		setPending();
		await user.click(screen.getByRole("radio", { name: "2" }));
		expect(screen.getByRole("radio", { name: "4" }).getAttribute("aria-checked")).toBe("true");
		expect(screen.getByRole("radio", { name: "2" }).getAttribute("aria-checked")).toBe("false");
	});
});
