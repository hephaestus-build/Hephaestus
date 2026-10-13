import { act, render, renderHook, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import type { MentorTurn } from "@/api/types.gen";

import { HephTurnStatus, useTurnStatusLabel } from "./HephTurnStatus";

type ShownTurn = Pick<MentorTurn, "status" | "surface" | "partial">;

beforeEach(() => {
	vi.useFakeTimers();
});

afterEach(() => {
	vi.useRealTimers();
});

it("says Heph is busy with other questions only once a queued turn has waited a second", () => {
	const { result, rerender } = renderHook((turn: ShownTurn) => useTurnStatusLabel(turn), {
		initialProps: { status: "QUEUED", surface: "APP" },
	});
	expect(result.current).toBe("Thinking…");

	act(() => {
		vi.advanceTimersByTime(999);
	});
	expect(result.current).toBe("Thinking…");
	act(() => {
		vi.advanceTimersByTime(1);
	});
	expect(result.current).toBe("Heph is answering other questions first…");

	rerender({
		status: "RUNNING",
		surface: "APP",
		partial: { activity: "WRITING", stage: "Thinking…", text: "Your" },
	});
	act(() => {
		vi.advanceTimersByTime(500);
	});
	expect(result.current).toBe("Writing…");
});

it("never shows the wait for a turn that starts within the second", () => {
	const { result, rerender } = renderHook((turn: ShownTurn) => useTurnStatusLabel(turn), {
		initialProps: { status: "QUEUED", surface: "APP" },
	});
	act(() => {
		vi.advanceTimersByTime(200);
	});
	rerender({ status: "RUNNING", surface: "APP" });
	act(() => {
		vi.advanceTimersByTime(2000);
	});
	expect(result.current).toBe("Thinking…");
});

it("shows the stage the host names for the tool in flight", () => {
	const { result } = renderHook(() =>
		useTurnStatusLabel({
			status: "RUNNING",
			surface: "APP",
			partial: {
				activity: "TOOL",
				tool: "fetch_context",
				stage: "Reading your pull requests and issues…",
				text: "",
			},
		}),
	);
	expect(result.current).toBe("Reading your pull requests and issues…");
});

const leaveNote = () => screen.queryByText(/You can leave\. The answer stays here\./u);

it("counts the wait and says the developer can leave from 10 s, and not before", () => {
	const asked = new Date("2026-10-12T09:00:00Z");
	vi.setSystemTime(new Date(asked.getTime() + 8000));
	render(
		<HephTurnStatus
			turn={{ createdAt: asked, status: "RUNNING", surface: "APP", partial: undefined }}
		/>,
	);

	act(() => {
		vi.advanceTimersByTime(1000);
	});
	expect(leaveNote()).toBeNull();

	act(() => {
		vi.advanceTimersByTime(1000);
	});
	expect(leaveNote()?.textContent).toBe(" 10 s · You can leave. The answer stays here.");

	act(() => {
		vi.advanceTimersByTime(2000);
	});
	expect(leaveNote()?.textContent).toBe(" 12 s · You can leave. The answer stays here.");
});

it("never tells a developer who asked in Slack to leave the app", () => {
	const asked = new Date("2026-10-12T09:00:00Z");
	vi.setSystemTime(new Date(asked.getTime() + 60_000));
	render(<HephTurnStatus turn={{ createdAt: asked, status: "RUNNING", surface: "SLACK" }} />);
	act(() => {
		vi.advanceTimersByTime(1000);
	});
	expect(screen.queryByText(/You can leave/u)).toBeNull();
});
