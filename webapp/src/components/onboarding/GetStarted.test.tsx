import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import {
	GetStarted as GetStartedCorner,
	type GetStartedProps,
	type GetStartedSetupRow,
	type GetStartedState,
} from "./GetStarted";

/** The corner, closed, as the route renders it. */
function GetStarted({ state }: Pick<GetStartedProps, "state">) {
	return (
		<GetStartedCorner
			state={state}
			open={false}
			opening="reader"
			onOpenChange={vi.fn()}
			onHide={vi.fn()}
		/>
	);
}

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

		// The last step turns done, and the status line says so.
		view.rerender(
			<GetStarted state={ready([row("ai-choice", "done"), row("first-review", "done")])} />,
		);
		expect(screen.getByRole("status").textContent).toBe(
			"Done: Your first practice review. Every step is done.",
		);
	});

	it("says nothing for a list that loads already done", () => {
		const view = render(<GetStarted state={{ status: "loading" }} />);
		view.rerender(<GetStarted state={ready([row("first-review", "done")])} />);
		expect(screen.getByRole("status").textContent).toBe("");
	});
});
