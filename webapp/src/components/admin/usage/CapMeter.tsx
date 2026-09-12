import type { ReactNode } from "react";

import { cn } from "cn";
import { Progress } from "@/components/ui/progress";
import { formatCapUsd, formatCostUsd } from "@/lib/money";

import { BUDGET_WARN_PERCENT } from "./usage-utils";

/** Whether a cap is worth naming as a state, and which one. `null` means "just a number". */
export type CapState = "paused" | "near" | null;

/**
 * A past month is never a state: caps are compared against *today's* limits, so a finished month can
 * be over one without anything being held back.
 */
export function capState(
	percent: number | undefined,
	paused: boolean,
	isCurrentMonth: boolean,
): CapState {
	if (!isCurrentMonth) {
		return null;
	}
	if (paused) {
		return "paused";
	}
	return percent != null && percent >= BUDGET_WARN_PERCENT ? "near" : null;
}

/** The tone never carries the state alone — every caller prints one of these beside the bar (SC 1.4.1). */
export const CAP_STATE_LABELS: Record<Exclude<CapState, null>, string> = {
	paused: "Paused",
	near: "Near cap",
};

export interface CapMeterProps {
	spendUsd: number;
	capUsd: number;
	/** Share of the cap consumed. May exceed 100; the bar clamps, the announcement does not. */
	percent: number;
	paused: boolean;
	/** Accessible name, distinct per meter: "Shared-model budget used", "Provider cap used by Acme". */
	label: string;
}

/**
 * The bar is all this owns — layout and the caption belong to the surface — but the tone, the
 * clamping and the announcement must not diverge between the surfaces that show a cap.
 */
export function CapMeter({ spendUsd, capUsd, percent, paused, label }: CapMeterProps): ReactNode {
	const value = Math.min(Math.max(percent, 0), 100);
	const rounded = Math.round(percent);
	// Comma, not an em-dash: screen readers render an em-dash inconsistently, and some spell it out.
	const valueText = `${rounded}% used, ${formatCostUsd(spendUsd)} of ${formatCapUsd(capUsd)}`;
	const tone =
		paused || percent >= 100
			? "**:data-[slot=progress-indicator]:bg-destructive"
			: percent >= BUDGET_WARN_PERCENT
				? "**:data-[slot=progress-indicator]:bg-warning"
				: "**:data-[slot=progress-indicator]:bg-primary";

	return (
		<Progress
			value={value}
			aria-label={label}
			getAriaValueText={() => valueText}
			className={cn("w-full *:data-[slot=progress-track]:h-1.5", tone)}
		/>
	);
}
