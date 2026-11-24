import { act, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";

import { ACTIVITY_PRESET_OPTIONS } from "@/components/activity/activity-period";

import { RangeControls } from "./RangeControls";

afterEach(() => {
	vi.useRealTimers();
});

it("announces a pending range update after a second and keeps it for half a second", () => {
	vi.useFakeTimers();
	const props = { options: ACTIVITY_PRESET_OPTIONS, range: "30d", onRangeChange: vi.fn() } as const;
	const { rerender } = render(<RangeControls {...props} updating />);
	const status = screen.getByRole("status");
	act(() => {
		vi.advanceTimersByTime(999);
	});
	expect(status.textContent).toBe("");
	act(() => {
		vi.advanceTimersByTime(1);
	});
	expect(status.textContent).toBe("Updating…");

	rerender(<RangeControls {...props} updating={false} />);
	act(() => {
		vi.advanceTimersByTime(499);
	});
	expect(status.textContent).toBe("Updating…");
	act(() => {
		vi.advanceTimersByTime(1);
	});
	expect(status.textContent).toBe("");
});
