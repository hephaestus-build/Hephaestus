import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { ProfileTimeframePicker } from "./ProfileTimeframePicker";

describe("ProfileTimeframePicker", () => {
	it("renders bookmarked dates without rewriting them", () => {
		const onTimeframeChange = vi.fn();
		const { rerender } = render(
			<ProfileTimeframePicker
				afterDate="2026-06-02T00:00:00"
				beforeDate="2026-06-07T00:00:00"
				onTimeframeChange={onTimeframeChange}
			/>,
		);
		expect(screen.getByRole("button", { name: "Choose custom dates" }).textContent).toContain(
			"Jun 2 – 6",
		);
		rerender(
			<ProfileTimeframePicker
				afterDate="2026-07-04T00:00:00"
				beforeDate="2026-07-09T00:00:00"
				onTimeframeChange={onTimeframeChange}
			/>,
		);
		expect(screen.getByRole("button", { name: "Choose custom dates" }).textContent).toContain(
			"Jul 4 – 8",
		);
		expect(onTimeframeChange).not.toHaveBeenCalled();
	});

	it("chooses a custom end day with an exclusive upper bound", async () => {
		const onTimeframeChange = vi.fn();
		render(
			<ProfileTimeframePicker
				afterDate="2026-06-02T00:00:00"
				onTimeframeChange={onTimeframeChange}
			/>,
		);
		await userEvent.click(screen.getByRole("button", { name: "Choose custom dates" }));
		await userEvent.click(screen.getByRole("button", { name: /June 9th, 2026/ }));
		expect(onTimeframeChange).toHaveBeenCalledExactlyOnceWith(
			expect.stringMatching(/^2026-06-02T00:00:00(?:Z|[+-]\d{2}:\d{2})$/),
			expect.stringMatching(/^2026-06-10T00:00:00(?:Z|[+-]\d{2}:\d{2})$/),
		);
	});
	it("keeps the calendar open while a controlled range changes", async () => {
		function ControlledPicker() {
			const [range, setRange] = useState<{ after: string; before?: string }>({
				after: "2026-06-02T00:00:00",
				before: "2026-06-07T00:00:00",
			});
			return (
				<ProfileTimeframePicker
					afterDate={range.after}
					beforeDate={range.before}
					onTimeframeChange={(after, before) => setRange({ after, before })}
				/>
			);
		}
		render(<ControlledPicker />);
		await userEvent.click(screen.getByRole("button", { name: "Choose custom dates" }));
		await userEvent.click(screen.getByRole("button", { name: /June 12th, 2026/ }));
		screen.getByRole("dialog");
		expect(screen.getByRole("button", { name: "Choose custom dates" }).textContent).toContain(
			"Jun 2 – 12",
		);
		await userEvent.click(screen.getByRole("button", { name: /June 15th, 2026/ }));
		screen.getByRole("dialog");
		expect(screen.getByRole("button", { name: "Choose custom dates" }).textContent).toContain(
			"Jun 2 – 15",
		);
	});
});
