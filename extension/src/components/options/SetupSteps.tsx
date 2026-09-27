import { CheckIcon } from "lucide-react";

import { cn } from "cn";

export type SetupStep = "connect" | "sign-in" | "sites";

const STEPS: readonly { step: SetupStep; label: string }[] = [
	{ step: "connect", label: "Connect" },
	{ step: "sign-in", label: "Sign in" },
	{ step: "sites", label: "Allow a site" },
];

export interface SetupStepsProps {
	/** The step the reader is on; every step before it is done. */
	current: SetupStep;
}

/**
 * Where the reader is in setting up: choosing an instance, signing in and allowing a site are three
 * separate decisions with three separate prompts, and this keeps them from blurring into one.
 */
export function SetupSteps({ current }: SetupStepsProps) {
	const currentIndex = STEPS.findIndex((entry) => entry.step === current);
	return (
		<ol aria-label="Setup" className="flex flex-wrap items-center gap-x-2 gap-y-2 text-sm">
			{STEPS.map((entry, index) => {
				const done = index < currentIndex;
				const active = index === currentIndex;
				return (
					<li
						key={entry.step}
						aria-current={active ? "step" : undefined}
						className="flex items-center gap-2"
					>
						{index === 0 ? null : <span aria-hidden className="h-px w-6 bg-border sm:w-10" />}
						<span
							aria-hidden
							className={cn(
								"flex size-6 items-center justify-center rounded-full border text-xs font-semibold",
								done && "border-mentor bg-mentor text-mentor-foreground",
								active && "border-mentor text-mentor",
								!done && !active && "border-border text-muted-foreground",
							)}
						>
							{done ? <CheckIcon className="size-3.5" /> : index + 1}
						</span>
						<span className={cn(active ? "font-medium text-foreground" : "text-muted-foreground")}>
							{entry.label}
							{done ? <span className="sr-only"> (done)</span> : null}
						</span>
					</li>
				);
			})}
		</ol>
	);
}
