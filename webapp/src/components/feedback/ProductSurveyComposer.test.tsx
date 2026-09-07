import { Link } from "@tanstack/react-router";
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { renderWithRouter } from "@/test/router-harness";

import { ProductSurveyComposer } from "./ProductSurveyComposer";

afterEach(cleanup);

describe("survey authoring", () => {
	it("publishes separate optional questions with stable ids after removal", async () => {
		const onSubmit = vi.fn(() => Promise.resolve(true));
		const user = userEvent.setup();
		await renderWithRouter(
			<ProductSurveyComposer workspaces={[]} isPending={false} onSubmit={onSubmit} />,
			"/",
		);
		await user.type(screen.getByRole("textbox", { name: "Title" }), "Review experience");
		await user.type(screen.getByRole("textbox", { name: "Purpose" }), "Decide what to improve");
		await user.type(screen.getByRole("textbox", { name: "Question" }), "Discard this question");
		await user.click(screen.getByRole("button", { name: "Add question" }));
		await user.click(screen.getByRole("button", { name: "Remove question 1" }));
		await user.type(screen.getByRole("textbox", { name: "Question" }), "What should improve?");
		await user.click(screen.getByRole("button", { name: "Add question" }));
		await user.type(
			within(screen.getByRole("group", { name: "Question 2" })).getByRole("textbox", {
				name: "Question",
			}),
			"Anything else?",
		);
		await user.click(screen.getByRole("button", { name: "Publish survey" }));
		expect(onSubmit).toHaveBeenCalledExactlyOnceWith(
			expect.objectContaining({
				title: "Review experience",
				questions: [
					expect.objectContaining({ prompt: "What should improve?", required: false }),
					expect.objectContaining({ prompt: "Anything else?", required: false }),
				],
			}),
		);
	});
	it("associates choice errors with their field and publishes normalized choices", async () => {
		const onSubmit = vi.fn(() => Promise.resolve(true));
		const user = userEvent.setup();
		await renderWithRouter(
			<ProductSurveyComposer workspaces={[]} isPending={false} onSubmit={onSubmit} />,
			"/",
		);
		await user.type(screen.getByRole("textbox", { name: "Title" }), "Experience");
		await user.type(
			screen.getByRole("textbox", { name: "Purpose" }),
			"Choose our next improvement",
		);
		await user.type(screen.getByRole("textbox", { name: "Question" }), "Where should we focus?");
		await user.click(screen.getByRole("combobox", { name: "Answer type" }));
		await user.click(await screen.findByRole("option", { name: "Single choice" }));
		const choices = screen.getByRole("textbox", { name: "Choices (one per line)" });
		await user.type(choices, "Reviews\nReviews");
		const message = screen.getByText("Each choice must be different.");
		expect(choices.getAttribute("aria-describedby")?.split(" ")).toContain(message.id);
		expect(choices.getAttribute("aria-invalid")).toBe("true");
		await user.clear(choices);
		await user.type(choices, " Reviews \n\nDocumentation ");
		await user.click(screen.getByRole("button", { name: "Publish survey" }));
		expect(onSubmit).toHaveBeenCalledExactlyOnceWith(
			expect.objectContaining({
				questions: [
					expect.objectContaining({ type: "SINGLE_CHOICE", options: ["Reviews", "Documentation"] }),
				],
			}),
		);
	});

	it("rejects a past end with an immediate start and preserves the draft for correction", async () => {
		const onSubmit = vi.fn(() => Promise.resolve(true));
		const user = userEvent.setup();
		await renderWithRouter(
			<ProductSurveyComposer workspaces={[]} isPending={false} onSubmit={onSubmit} />,
			"/",
		);
		await user.type(screen.getByRole("textbox", { name: "Title" }), "Experience");
		await user.type(screen.getByRole("textbox", { name: "Purpose" }), "Decide what to improve");
		await user.type(screen.getByRole("textbox", { name: "Question" }), "What should improve?");
		const end = screen.getByLabelText("End (optional)");
		fireEvent.change(end, { target: { value: "2000-01-01T12:00" } });
		await user.click(screen.getByRole("button", { name: "Publish survey" }));
		expect(onSubmit).not.toHaveBeenCalled();
		const message = screen.getByText("The end must be after the start.");
		expect(end.getAttribute("aria-describedby")?.split(" ")).toContain(message.id);
		expect(end.getAttribute("aria-invalid")).toBe("true");
		fireEvent.change(end, { target: { value: "" } });
		await user.click(screen.getByRole("button", { name: "Publish survey" }));
		expect(onSubmit).toHaveBeenCalledExactlyOnceWith(
			expect.objectContaining({ title: "Experience", endsAt: undefined }),
		);
	});

	it("prevents changing custom controls while publishing", async () => {
		const user = userEvent.setup();
		await renderWithRouter(
			<ProductSurveyComposer workspaces={[]} isPending onSubmit={vi.fn()} />,
			"/",
		);
		const required = screen.getByRole("checkbox", { name: "Required" });
		await user.click(required);
		expect(required.getAttribute("aria-checked")).toBe("false");
		await user.click(screen.getByRole("combobox", { name: "Answer type" }));
		expect(screen.queryByRole("listbox")).toBeNull();
	});
	it("keeps an authoring draft when navigation is cancelled", async () => {
		const user = userEvent.setup();
		const { router } = await renderWithRouter(
			<>
				<ProductSurveyComposer workspaces={[]} isPending={false} onSubmit={vi.fn()} />
				<Link to="/">Leave authoring</Link>
			</>,
			"/authoring",
		);
		await user.type(screen.getByRole("textbox", { name: "Title" }), "Keep this draft");
		await user.click(screen.getByRole("link", { name: "Leave authoring" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await user.click(dialog.getByRole("button", { name: "Keep editing" }));
		expect(router.state.location.pathname).toBe("/authoring");
		screen.getByDisplayValue("Keep this draft");
	});
});
