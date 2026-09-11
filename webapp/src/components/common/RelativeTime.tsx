import { format, formatDistance } from "date-fns";
import { ClockAlertIcon, TriangleAlertIcon } from "lucide-react";

import { cn } from "cn";
import { useNow } from "@/components/common/use-now";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { asDate } from "@/lib/dates";

/** `unknown` is a timestamp with no known cadence; `never` is no timestamp at all. */
export type FreshnessTone = "never" | "unknown" | "fresh" | "stale" | "veryStale";

/** `fresh` and `unknown` stay muted: only an adverse judgement earns a colour. */
const FRESHNESS_CLASS: Record<FreshnessTone, string> = {
	never: "text-muted-foreground",
	unknown: "text-muted-foreground",
	fresh: "text-muted-foreground",
	stale: "text-warning",
	veryStale: "text-destructive",
};

const FRESHNESS_LABEL = {
	stale: "Stale",
	veryStale: "Very stale",
} as const;

export interface RelativeTimeProps {
	value?: Date | string | null;
	tone?: FreshnessTone;
	/** Never pass "now" or similar: an absent time must not read as fresh. */
	fallback?: string;
	/** Turn off only inside a hover surface that already states the absolute time. */
	tooltip?: boolean;
	className?: string;
}

/**
 * A relative timestamp against a clock shared by every relative time on the page, with the absolute
 * instant one hover away for correlating a row against a server log.
 */
export function RelativeTime({
	value,
	tone,
	fallback = "–",
	tooltip = true,
	className,
}: RelativeTimeProps) {
	const currentNow = useNow();
	const date = asDate(value);

	if (!date) {
		return <span className={cn("text-muted-foreground", className)}>{fallback}</span>;
	}

	const toneClass = tone ? FRESHNESS_CLASS[tone] : undefined;
	const text = formatDistance(date, currentNow, { addSuffix: true });
	const adverseTone = tone === "stale" || tone === "veryStale" ? tone : undefined;
	const statusLabel = adverseTone ? FRESHNESS_LABEL[adverseTone] : undefined;
	const StatusIcon = adverseTone === "veryStale" ? TriangleAlertIcon : ClockAlertIcon;
	const reading = (
		<span className={cn("inline-flex items-center gap-1", toneClass, className)}>
			{adverseTone && <StatusIcon className="size-3.5 shrink-0" aria-hidden />}
			<span>{text}</span>
			{statusLabel && <span className="sr-only">, {statusLabel}</span>}
		</span>
	);

	if (!tooltip) {
		return reading;
	}

	return (
		<Tooltip>
			<TooltipTrigger
				className={cn(
					"cursor-help underline decoration-dotted decoration-muted-foreground/40 underline-offset-4",
				)}
			>
				{reading}
			</TooltipTrigger>
			<TooltipContent>
				{statusLabel && <>{statusLabel} · </>}
				<span className="tabular-nums">{format(date, "d MMM yyyy, HH:mm:ss")}</span>
			</TooltipContent>
		</Tooltip>
	);
}
