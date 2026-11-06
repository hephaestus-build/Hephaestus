import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { InfoTip } from "@/components/common/InfoTip";
import { Section } from "./Section";

afterEach(cleanup);

it("keeps the info control outside the heading and retains the section's name", () => {
	render(
		<Section
			title="Review guidance"
			info={<InfoTip label="About review guidance">What a review checks.</InfoTip>}
		>
			Section body.
		</Section>,
	);
	const heading = screen.getByRole("heading", { name: "Review guidance" });
	expect(within(heading).queryByRole("button")).toBeNull();
	screen.getByRole("region", { name: "Review guidance" });
	fireEvent.click(screen.getByRole("button", { name: "About review guidance" }));
	screen.getByText("What a review checks.");
});
