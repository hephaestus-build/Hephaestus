import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { FxRateInfo } from "@/api/types.gen";

import { BudgetAmountDialog, type BudgetAmountDialogProps } from "./BudgetAmountDialog";

const eur: FxRateInfo = {
	currencyCode: "EUR",
	ratePerUsd: 0.879,
	rateDate: new Date("2026-07-24T00:00:00.000Z"),
	source: "ECB",
};

function renderDialog(overrides: Partial<BudgetAmountDialogProps> = {}) {
	return render(
		<BudgetAmountDialog
			open
			title="Set monthly cap"
			description="At the cap, that work pauses until the month resets."
			fieldLabel="Monthly cap (USD)"
			currentValueUsd={50}
			isPending={false}
			fx={eur}
			isCurrentMonth
			onOpenChange={vi.fn<() => void>()}
			onSubmit={vi.fn<() => void>()}
			{...overrides}
		/>,
	);
}

describe("the cap editor's currency hint", () => {
	it("withdraws the estimate when the month behind the open dialog closes", () => {
		const { rerender } = renderDialog();
		screen.getByText(/at today's rate/iu);

		rerender(
			<BudgetAmountDialog
				open
				title="Set monthly cap"
				description="At the cap, that work pauses until the month resets."
				fieldLabel="Monthly cap (USD)"
				currentValueUsd={50}
				isPending={false}
				fx={eur}
				isCurrentMonth={false}
				onOpenChange={vi.fn<() => void>()}
				onSubmit={vi.fn<() => void>()}
			/>,
		);

		expect(screen.queryByText(/at today's rate/iu)).toBeNull();
		expect(screen.queryByLabelText(/approximately 44 euros/iu)).toBeNull();
	});

	it("says nothing about a rate a caller never claimed a month for", () => {
		renderDialog({ isCurrentMonth: undefined });

		expect(screen.queryByText(/at today's rate/iu)).toBeNull();
	});
});
