import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";

import { InfoTip } from "./InfoTip";

afterEach(async () => {
	cleanup();
	await vi.runOnlyPendingTimersAsync();
	vi.useRealTimers();
});

it("skips the hover delay for 500 ms after a tip closes, then restores the first-tip delay", async () => {
	vi.useFakeTimers();
	render(
		<>
			<InfoTip label="First tip">First explanation.</InfoTip>
			<InfoTip label="Second tip">Second explanation.</InfoTip>
		</>,
	);
	const first = screen.getByRole("button", { name: "First tip" });
	const second = screen.getByRole("button", { name: "Second tip" });
	// A press opens at once and starts the shared skip window; the second press closes it.
	fireEvent.click(first);
	screen.getByRole("dialog", { name: "First tip" });
	fireEvent.click(first);
	await act(async () => vi.advanceTimersByTimeAsync(499));
	fireEvent.pointerEnter(second, { pointerType: "mouse" });
	fireEvent.mouseEnter(second);
	await act(async () => vi.advanceTimersByTimeAsync(0));
	screen.getByRole("dialog", { name: "Second tip" });
	fireEvent.keyDown(second, { key: "Escape" });
	expect(screen.queryByRole("dialog", { name: "Second tip" })).toBeNull();
	fireEvent.mouseLeave(second);
	await act(async () => vi.advanceTimersByTimeAsync(500));
	fireEvent.pointerEnter(first, { pointerType: "mouse" });
	fireEvent.mouseEnter(first);
	fireEvent.mouseMove(first);
	await act(async () => vi.advanceTimersByTimeAsync(149));
	expect(screen.queryByRole("dialog", { name: "First tip" })).toBeNull();
	await act(async () => vi.advanceTimersByTimeAsync(1));
	screen.getByRole("dialog", { name: "First tip" });
});
