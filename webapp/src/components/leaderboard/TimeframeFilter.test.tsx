import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { TimeframeFilter } from "./TimeframeFilter";

vi.mock("@/components/common/use-now", () => ({
	useNow: () => new Date(2026, 8, 16, 12).getTime(),
}));

const bookmark = { after: "2026-09-02T09:45:00+02:00", before: "2026-09-08T18:30:00+02:00" };

function ControlledFilter({ openEndedPresets = false }: { openEndedPresets?: boolean }) {
	const [range, setRange] = useState<{ after: string; before?: string }>(bookmark);
	return (
		<>
			<TimeframeFilter
				openEndedPresets={openEndedPresets}
				afterDate={range.after}
				beforeDate={range.before}
				onTimeframeChange={(after, before) => setRange({ after, before })}
			/>
			<output aria-label="Selected interval">
				{range.after}/{range.before}
			</output>
			<button type="button" onClick={() => setRange(bookmark)}>
				Restore bookmark
			</button>
		</>
	);
}

describe("TimeframeFilter", () => {
	it("preserves precise bookmarked dates on mount and after external navigation", async () => {
		render(<ControlledFilter />);
		const user = userEvent.setup();
		const interval = `${bookmark.after}/${bookmark.before}`;
		expect(screen.getByLabelText("Selected interval").textContent).toBe(interval);
		expect(screen.getByRole("combobox", { name: "Timeframe" }).textContent).toContain(
			"Custom range",
		);
		await user.click(screen.getByRole("combobox", { name: "Timeframe" }));
		await user.click(await screen.findByRole("option", { name: "Last week" }));
		expect(screen.getByRole("combobox", { name: "Timeframe" }).textContent).toContain("Last week");
		await user.click(screen.getByRole("button", { name: "Restore bookmark" }));
		expect(screen.getByLabelText("Selected interval").textContent).toBe(interval);
		expect(screen.getByRole("combobox", { name: "Timeframe" }).textContent).toContain(
			"Custom range",
		);
	});

	it("only changes the interval after the user chooses a custom day", async () => {
		render(<ControlledFilter />);
		const user = userEvent.setup();
		await user.click(screen.getByRole("button", { name: "Choose custom dates" }));
		expect(screen.getByLabelText("Selected interval").textContent).toBe(
			`${bookmark.after}/${bookmark.before}`,
		);
		await user.click(screen.getByRole("button", { name: /September 12th, 2026/ }));
		expect(screen.getByLabelText("Selected interval").textContent).toMatch(
			/^2026-09-02T00:00:00(?:Z|[+-]\d{2}:\d{2})\/2026-09-13T00:00:00(?:Z|[+-]\d{2}:\d{2})$/,
		);
		screen.getByRole("dialog");
	});
	it.each([
		{ preset: "This week", after: "2026-09-14T09:00:00", before: "2026-09-21T09:00:00" },
		{ preset: "This month", after: "2026-09-01T00:00:00", before: "2026-10-01T00:00:00" },
	])("bounds $preset to the complete leaderboard period", async ({ preset, after, before }) => {
		render(<ControlledFilter />);
		const user = userEvent.setup();
		await user.click(screen.getByRole("combobox", { name: "Timeframe" }));
		await user.click(await screen.findByRole("option", { name: preset }));
		expect(
			screen
				.getByLabelText("Selected interval")
				.textContent.split("/")
				.map((date) => date.slice(0, 19)),
		).toStrictEqual([after, before]);
	});

	it("keeps the upper bound open when requested", async () => {
		render(<ControlledFilter openEndedPresets />);
		const user = userEvent.setup();
		await user.click(screen.getByRole("combobox", { name: "Timeframe" }));
		await user.click(await screen.findByRole("option", { name: "This month" }));
		expect(screen.getByLabelText("Selected interval").textContent).toMatch(
			/^2026-09-01T00:00:00(?:Z|[+-]\d{2}:\d{2})\/$/,
		);
	});
});
