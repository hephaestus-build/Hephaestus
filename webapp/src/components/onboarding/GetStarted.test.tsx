import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { GetStarted, type GetStartedSetupRow, type GetStartedState } from "./GetStarted";

const row = (id: string, status: GetStartedSetupRow["status"]): GetStartedSetupRow => ({
	id,
	title: id === "ai-choice" ? "Choose how AI handles your work" : "Your first practice review",
	status,
	summary: "",
});

const ready = (setup: GetStartedSetupRow[]): GetStartedState => ({
	status: "ready",
	setup,
	learn: [],
});

describe("GetStarted", () => {
	it("announces a step that turns done while the page is open, and nothing on the first render", () => {
		const view = render(
			<GetStarted state={ready([row("ai-choice", "done"), row("first-review", "to-do")])} />,
		);
		expect(screen.getByRole("status").textContent).toBe("");

		// A poll that changed nothing says nothing.
		view.rerender(
			<GetStarted state={ready([row("ai-choice", "done"), row("first-review", "to-do")])} />,
		);
		expect(screen.getByRole("status").textContent).toBe("");

		view.rerender(
			<GetStarted state={ready([row("ai-choice", "done"), row("first-review", "in-progress")])} />,
		);
		expect(screen.getByRole("status").textContent).toBe("");

		// The last step turns done: the list goes, and its status line says why.
		view.rerender(
			<GetStarted state={ready([row("ai-choice", "done"), row("first-review", "done")])} />,
		);
		expect(screen.getByRole("status").textContent).toBe(
			"Done: Your first practice review. Every step is done.",
		);
		expect(screen.queryByRole("region", { name: "Get started" })).toBeNull();
	});

	it("says nothing for a list that loads already done", () => {
		const view = render(<GetStarted state={{ status: "loading" }} />);
		view.rerender(<GetStarted state={ready([row("first-review", "done")])} />);
		expect(screen.getByRole("status").textContent).toBe("");
	});
});
