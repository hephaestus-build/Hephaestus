import { useId } from "react";

import { cn } from "cn";
import { AUTONOMY_DEFS } from "@/components/practice-vocabulary/autonomy-defs";
import { FieldLabel } from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
	PRACTICE_AUTONOMY_ADDS,
	PRACTICE_AUTONOMY_LABELS,
	PRACTICE_AUTONOMY_ORDER,
	type PracticeAutonomy,
} from "@/lib/practice-autonomy";

export interface AutonomyLadderProps {
	label: string;
	value: PracticeAutonomy;
	onChange: (autonomy: PracticeAutonomy) => void;
	disabled?: boolean;
	variant?: "full" | "compact";
	muted?: boolean;
	className?: string;
}

export function AutonomyLadder({
	label,
	value,
	onChange,
	disabled = false,
	variant = "compact",
	muted = false,
	className,
}: AutonomyLadderProps) {
	const full = variant === "full";
	const rungIdPrefix = useId();

	return (
		<RadioGroup
			aria-label={label}
			value={value}
			disabled={disabled}
			onValueChange={(next) => {
				if (next !== value) onChange(next);
			}}
			className={cn(
				"grid min-w-0 grid-cols-1 gap-px overflow-hidden rounded-lg border bg-border",
				"sm:grid-cols-3",
				muted && "opacity-70",
				className,
			)}
		>
			{PRACTICE_AUTONOMY_ORDER.map((autonomy) => {
				const Icon = AUTONOMY_DEFS[autonomy].icon;
				const titleId = `${rungIdPrefix}-${autonomy}`;
				const addsId = `${rungIdPrefix}-${autonomy}-adds`;
				const selected = autonomy === value;
				return (
					<FieldLabel
						key={autonomy}
						className={cn(
							"w-full min-w-0 cursor-pointer items-center gap-2 bg-background p-2 font-normal transition-colors",
							"has-data-unchecked:hover:bg-muted/60 has-data-checked:bg-accent",
							"has-data-disabled:cursor-not-allowed has-data-disabled:opacity-60",
							full && "flex-col items-start gap-1.5 p-3",
						)}
					>
						<span className="flex min-w-0 items-center gap-2">
							<RadioGroupItem
								value={autonomy}
								aria-labelledby={titleId}
								aria-describedby={full ? addsId : undefined}
							/>
							<Icon
								className={cn(
									"size-4 shrink-0 text-muted-foreground",
									selected && "text-foreground",
								)}
								aria-hidden
							/>
							<span id={titleId} className={cn("truncate text-sm", selected && "font-medium")}>
								{PRACTICE_AUTONOMY_LABELS[autonomy]}
							</span>
						</span>
						{full && (
							<span id={addsId} className="ps-6 text-muted-foreground text-xs">
								{PRACTICE_AUTONOMY_ADDS[autonomy]}
							</span>
						)}
					</FieldLabel>
				);
			})}
		</RadioGroup>
	);
}
