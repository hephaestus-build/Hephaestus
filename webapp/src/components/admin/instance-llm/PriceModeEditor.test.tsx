import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { PriceModeEditor } from "./PriceModeEditor";

describe("PriceModeEditor", () => {
	it("prices reasoning through billable output instead of a second overlapping rate", () => {
		render(
			<PriceModeEditor
				audience="instance"
				idPrefix="test-price"
				value={{ pricingMode: "PRICED" }}
				onChange={vi.fn<() => void>()}
			/>,
		);
		screen.getByLabelText(/Input \(USD\)/u);
		screen.getByLabelText(/Output \(USD\)/u);
		expect(screen.queryByLabelText(/Reasoning \(USD\)/u)).toBeNull();
		screen.getByText(/reasoning tokens are included in output/iu);
	});

	it("describes an intentional zero API rate without calling infrastructure free", () => {
		render(
			<PriceModeEditor
				audience="instance"
				idPrefix="test-price"
				value={{ pricingMode: "NO_CHARGE" }}
				onChange={vi.fn<() => void>()}
			/>,
		);
		screen.getByText("No metered API cost");
		expect(screen.queryByText(/^Free$/u)).toBeNull();
		screen.getByText(/infrastructure cost may still apply/iu);
	});
});
