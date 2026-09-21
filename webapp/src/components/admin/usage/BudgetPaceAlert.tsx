import { TrendingUp } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { formatCapUsd, formatCostUsd } from "@/lib/money";

import { type Fx, FxAmount, spendConversion, spendOfCapConversion } from "./fx";
import { type BudgetProjection, formatDayLabel } from "./usage-utils";

export interface BudgetPaceAlertProps {
	scope: "provider" | "shared";
	/** Absent on the workspace's own page ("You've used …"), the name on the instance console. */
	subjectName?: string;
	percent: number;
	spendUsd: number;
	capUsd: number | undefined;
	/** `null` when the month is too young or the spend too empty for a pace to mean anything. */
	projection: BudgetProjection | null;
	fx: Fx;
}

/** Warn before the wall: how much of a cap is gone, and when this month's pace would reach it. */
export function BudgetPaceAlert({
	scope,
	subjectName,
	percent,
	spendUsd,
	capUsd,
	projection,
	fx,
}: BudgetPaceAlertProps) {
	const capName = scope === "provider" ? "provider cap" : "shared-model budget";
	// Short, because the title one line up has already said which purse this is.
	const capNoun = scope === "provider" ? "cap" : "budget";
	return (
		<Alert variant="warning" role="status">
			<TrendingUp aria-hidden />
			<AlertTitle>
				{subjectName == null
					? `You've used ${Math.round(percent)}% of your ${capName}`
					: `${subjectName} has used ${Math.round(percent)}% of its ${capName}`}
			</AlertTitle>
			<AlertDescription>
				<p>
					{formatCostUsd(spendUsd)} of {formatCapUsd(capUsd)}
					<FxAmount conversion={spendOfCapConversion(spendUsd, capUsd, fx)} />.
					{projection != null &&
						(projection.reachedOn == null ? (
							// The month-end figure converts too, or the sentence switches currency mid-breath.
							<>
								{` At this pace, the month finishes around ${formatCostUsd(projection.projectedMonthEndUsd)}`}
								<FxAmount conversion={spendConversion(projection.projectedMonthEndUsd, fx)} />.
							</>
						) : (
							` At this pace, the ${capNoun} is reached around ${formatDayLabel(projection.reachedOn)}.`
						))}
				</p>
			</AlertDescription>
		</Alert>
	);
}
