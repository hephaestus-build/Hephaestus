import { ChevronDownIcon, CircleAlertIcon, LoaderIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import { HephMark } from "~/components/brand/HephaestusLogo";
import type { ReportSummary } from "~/components/report/report-summary";
import { REPORT_ROW_HEIGHT } from "~/shared/frame-messages";

export interface ReportRowProps {
	summary: ReportSummary;
	expanded: boolean;
	/** The id of the details this row opens. */
	controls: string;
	onToggle: () => void;
	/** The line's one next step, when its state has one. */
	action?: ReactNode;
}

/**
 * The line's status, as a merge request report leads with one (Pajamas): the Hephaestus mark in a
 * tinted circle, with the tone as a small badge on it. The mark stays in every tone and at every
 * width, so a warning here never passes for the provider's own verdict; the tone's words are the
 * line's text, so the badge is decoration.
 */
const TONE: Record<ReportSummary["tone"], { circle: string; badge: ReactNode }> = {
	neutral: { circle: "bg-muted", badge: null },
	progress: {
		circle: "bg-muted",
		badge: (
			<LoaderIcon
				aria-hidden
				className="size-3 animate-spin text-muted-foreground motion-reduce:animate-none"
			/>
		),
	},
	error: {
		circle: "bg-destructive/15",
		badge: <CircleAlertIcon aria-hidden className="size-3 text-destructive" />,
	},
};

function Status({ tone }: { tone: ReportSummary["tone"] }) {
	const { circle, badge } = TONE[tone];
	return (
		<span
			data-tone={tone}
			className={cn(
				"relative flex size-6 shrink-0 items-center justify-center rounded-full",
				circle,
			)}
		>
			<HephMark className="size-4" />
			{badge === null ? null : (
				<span className="absolute -right-1 -bottom-1 flex size-4 items-center justify-center rounded-full bg-card">
					{badge}
				</span>
			)}
		</span>
	);
}

/**
 * The report's collapsed line — the first level of the report, as the provider's own merge request
 * reports have it: a status, the title and what is true, the one action, and the toggle. It is the
 * same height in every state: the page can measure the frame, so its height must not tell it what the
 * line says. A heading holds the disclosure button (the WAI-ARIA disclosure pattern), so the report
 * is one landmark a screen reader can jump to and one Tab stop to open.
 */
export function ReportRow({ summary, expanded, controls, onToggle, action }: ReportRowProps) {
	return (
		<div className="flex items-center gap-2 pr-3" style={{ height: REPORT_ROW_HEIGHT }}>
			<h2 className="m-0 flex h-full min-w-0 flex-1 text-sm font-normal">
				<button
					type="button"
					aria-expanded={expanded}
					aria-controls={controls}
					onClick={onToggle}
					className="group flex h-full min-w-0 flex-1 items-center gap-2 pl-4 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
				>
					<Status tone={summary.tone} />
					<span className="flex min-w-0 items-baseline gap-2">
						<span className="shrink-0 font-semibold group-hover:underline @max-[27rem]:sr-only">
							Practice review
						</span>
						<span
							className={cn(
								"min-w-0 truncate",
								summary.tone === "neutral" || summary.tone === "progress"
									? "text-muted-foreground"
									: "text-foreground",
							)}
						>
							{summary.text}
						</span>
					</span>
					<ChevronDownIcon
						aria-hidden
						className={cn(
							"ml-auto size-4 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none",
							expanded && "rotate-180",
						)}
					/>
				</button>
			</h2>
			{action}
		</div>
	);
}
