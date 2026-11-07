import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";

import { InfoTip } from "./InfoTip";

afterEach(cleanup);

it("uses the short label as the panel lead when no separate lead is supplied", () => {
	render(<InfoTip label="About these figures">Counted over the last 30 days.</InfoTip>);
	fireEvent.click(screen.getByRole("button", { name: "About these figures" }));
	const panel = within(screen.getByRole("dialog", { name: "About these figures" }));
	panel.getByText("About these figures", { exact: true });
	panel.getByText("Counted over the last 30 days.");
});

it("uses an explanatory lead while keeping the button and panel's accessible name", () => {
	render(
		<InfoTip label="About these figures" lead="These figures describe your reviewed work.">
			Counted over the last 30 days.
		</InfoTip>,
	);
	fireEvent.click(screen.getByRole("button", { name: "About these figures" }));
	const panel = within(screen.getByRole("dialog", { name: "About these figures" }));
	panel.getByText("These figures describe your reviewed work.");
	expect(panel.queryByText("About these figures", { exact: true })).toBeNull();
	panel.getByText("Counted over the last 30 days.");
});
