import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Toaster } from "@/components/ui/sonner";
import { toast } from "@/lib/toast";

// The keyboard path (Alt+T, Tab, Enter) needs a real browser's focus: `ui/sonner.stories.tsx`.

/** Sonner's own lifetime for a toast without a `duration`. */
const SONNER_LIFETIME_MS = 4000;

async function passTime(ms: number) {
	await act(async () => vi.advanceTimersByTimeAsync(ms));
}

describe("toast lifetime", () => {
	beforeEach(() => {
		vi.useFakeTimers({ shouldAdvanceTime: true });
	});
	afterEach(() => {
		vi.useRealTimers();
	});

	it.each([
		["error", () => toast.error("We could not save the budget")],
		["warning", () => toast.warning("We could not save the budget")],
	])("keeps a %s until the reader closes it", async (_kind, show) => {
		const user = userEvent.setup();
		render(<Toaster />);
		show();

		await passTime(SONNER_LIFETIME_MS * 15);
		screen.getByText("We could not save the budget");

		await user.click(screen.getByRole("button", { name: "Close message" }));
		await passTime(1000);
		expect(screen.queryByText("We could not save the budget")).toBeNull();
	});

	it("lets a success without an action leave on its own", async () => {
		render(<Toaster />);
		toast.success("Budget saved");

		await passTime(100);
		screen.getByText("Budget saved");
		await passTime(SONNER_LIFETIME_MS + 1000);
		expect(screen.queryByText("Budget saved")).toBeNull();
	});
});
