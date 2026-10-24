import { BookOpenIcon } from "lucide-react";
import { type ReactNode, useId, useRef } from "react";

import { cn } from "cn";
import { DotSeparator } from "@/components/common/DotSeparator";
import { type BadgeVariant, type StatusDef, statusValues } from "@/components/common/status-def";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";
import { DEVELOPER_ANSWER_DEFS } from "@/components/practice-vocabulary/developer-answer-defs";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

import { GlossaryTerm } from "./GlossaryTerm";
import { SLOW_GAP_SECONDS } from "./lineage-chain";
import { ConnectorSample, connectorTone } from "./LineageConnectors";
import { resultChipClass } from "./LineageResultChip";
import {
	type Dot,
	FeedbackLetter,
	HereBadge,
	hereFrame,
	MergeIcon,
	RailDot,
	SlowRail,
	StageIcon,
	StepTag,
	ToneIcon,
} from "./LineageStep";
import { TRACE_TERMS, type TraceTerm } from "./trace-glossary";
import { STAGE_HEADERS, STAGE_MARKS, STAGE_ORDER } from "./trace-stage-headers";
import { TRACE_END_LABELS, TRACE_END_TONES, type TraceEndKind } from "./trace-verdict";

export interface TraceLegendProps {
	/** Whether the legend shows. The trace's "Legend" button sets it. */
	open: boolean;
	onOpenChange: (open: boolean) => void;
}

/** The dot colours, each with what it says on every step that has one. */
const DOTS: readonly { dot: Dot; label: string; text: string }[] = [
	{ dot: { tone: "success" }, label: "Went well", text: "met, delivered or worked" },
	{
		dot: { tone: "destructive" },
		label: "Not met or failed",
		text: "a practice not met, a delivery failed",
	},
	{ dot: { tone: "warning" }, label: "Lost or late", text: "withheld, not arrived, a long wait" },
	{ dot: { tone: "default" }, label: "Answered or disagreed", text: "the answer stays private" },
	{ dot: { tone: "secondary" }, label: "Open", text: "no outcome yet" },
	{
		dot: { tone: "outline", hollow: true },
		label: "Hollow",
		text: "nothing to judge, as an event",
	},
];

const ENDS: readonly { kind: TraceEndKind; text: string }[] = [
	{ kind: "delivered", text: "delivered to the developer" },
	{ kind: "lost", text: "withheld, rejected, replaced or failed" },
	{ kind: "open", text: "on its way, or no answer yet" },
	{ kind: "none", text: "nothing had to be sent" },
];

/** The marks a stage's header can carry, in the tone each most often takes. */
const MARKS: readonly { kind: keyof typeof STAGE_MARKS; tone: BadgeVariant }[] = [
	{ kind: "failed", tone: "destructive" },
	{ kind: "lost", tone: "warning" },
	{ kind: "slow", tone: "warning" },
	{ kind: "waiting", tone: "outline" },
];

const DESTINATIONS: readonly StatusDef[] = statusValues(FEEDBACK_DESTINATION_DEFS).map(
	(destination) => FEEDBACK_DESTINATION_DEFS[destination],
);

// Every state the rail can draw, from the registry it draws them with.
const DELIVERY: readonly StatusDef[] = statusValues(DELIVERY_STATE_DEFS).map(
	(state) => DELIVERY_STATE_DEFS[state],
);

const ANSWERS: readonly StatusDef[] = statusValues(DEVELOPER_ANSWER_DEFS).map(
	(answer) => DEVELOPER_ANSWER_DEFS[answer],
);

const TERMS: readonly TraceTerm[] = ["event", "result", "withheld", "coalesced"];

const SLOW_MINUTES = Math.round(SLOW_GAP_SECONDS / 60);

/**
 * The "Legend" button and the legend it opens over the trace, so the rail stays where it is. Each
 * sample is drawn by the same part the rail draws it with: the stage icons and marks, the dots, the
 * frame, the chips and letters, the connectors, the dashed wait, the merge and the icons, each with
 * a short name and one line. Under them, the terms and what the trace cannot show.
 */
export function TraceLegend({ open, onOpenChange }: TraceLegendProps) {
	const cannotId = useId();
	const title = useRef<HTMLHeadingElement>(null);
	return (
		<Popover open={open} onOpenChange={onOpenChange}>
			<PopoverTrigger render={<Button variant="ghost" size="sm" />}>
				<BookOpenIcon aria-hidden data-icon="inline-start" />
				Legend
			</PopoverTrigger>
			<PopoverContent
				align="end"
				role="region"
				aria-label="Legend"
				// It opens at its top, not on the first term far down, so nothing scrolls out of view.
				initialFocus={title}
				className="max-h-[min(40rem,80vh)] w-[min(44rem,calc(100vw-2rem))] gap-0 overflow-y-auto p-0"
			>
				<div className="text-xs">
					<div className="border-b px-4 pt-3 pb-2.5">
						<h3 ref={title} tabIndex={-1} className="text-sm font-semibold outline-none">
							How to read the trace
						</h3>
						<p className="text-muted-foreground">Each mark as the trace draws it.</p>
					</div>
					<div className="grid gap-x-8 gap-y-4 px-4 py-3 sm:grid-cols-2">
						<div className="space-y-4">
							<LegendGroup title="Stages">
								{STAGE_ORDER.map((stage) => (
									<Entry key={stage} mark={<StageIcon stage={stage} />}>
										<Name>{STAGE_HEADERS[stage].label}</Name> {STAGE_HEADERS[stage].holds}
									</Entry>
								))}
								<li className="space-y-1 pt-0.5">
									<span className="flex flex-wrap gap-1">
										{MARKS.map(({ kind, tone }) => (
											<Badge key={kind} variant={tone} size="xs">
												{STAGE_MARKS[kind]}
											</Badge>
										))}
									</span>
									<span className="block text-muted-foreground">
										The stage where the sentence on top says the path stopped or slowed
									</span>
								</li>
							</LegendGroup>
							<LegendGroup title="Steps">
								{DOTS.map(({ dot, label, text }) => (
									<Entry key={label} mark={<RailDot dot={dot} />}>
										<Name>{label}</Name> {text}
									</Entry>
								))}
								<Entry mark={<RailDot dot={{ tone: "success", here: true }} />}>
									<HereBadge /> the record you opened the trace from
								</Entry>
								<Entry mark={<span className={cn("h-3.5 w-5 rounded-md", hereFrame(false))} />}>
									<Name>Framed</Name> the step of that record, with its facts under it
								</Entry>
							</LegendGroup>
							<LegendGroup title="Where a path ends">
								{ENDS.map(({ kind, text }) => (
									<Entry
										key={kind}
										mark={
											<RailDot dot={{ tone: TRACE_END_TONES[kind], hollow: kind === "none" }} />
										}
									>
										<Name>{TRACE_END_LABELS[kind]}</Name> {text}
									</Entry>
								))}
							</LegendGroup>
						</div>
						<div className="space-y-4">
							<LegendGroup title="Observations and feedback">
								<Entry mark={<Chip className={resultChipClass({ outcome: "NOT_MET" })}>3</Chip>}>
									<Name>Observation</Name> by number, in its outcome’s colour
								</Entry>
								<Entry
									mark={<Chip className={resultChipClass({ outcome: "DOES_NOT_APPLY" })}>4</Chip>}
								>
									<Name>Does not apply</Name> hollow, unlike could not determine
								</Entry>
								<Entry
									mark={
										<Chip className={resultChipClass({ outcome: "MET", ringed: true })}>1</Chip>
									}
								>
									<Name>Ringed</Name> the observation you started from
								</Entry>
								<Entry mark={<FeedbackLetter letter="A" />}>
									<Name>Feedback</Name> by letter, with its observations under From
								</Entry>
							</LegendGroup>
							<LegendGroup title="Connections">
								<Entry
									mark={<ConnectorSample className={connectorTone("destructive", "strong")} />}
								>
									<Name>Line</Name> from an observation to each piece written from it
								</Entry>
								<Entry mark={<ConnectorSample className={connectorTone("destructive", "faint")} />}>
									<Name>Grey line</Name> off the path of your record
								</Entry>
							</LegendGroup>
							<LegendGroup title="Rail">
								<Entry mark={<SlowRail />}>
									<Name>Dashed</Name> a wait of {SLOW_MINUTES} min or more, its time{" "}
									<span className="font-medium text-warning">in amber</span>
								</Entry>
								<Entry mark={<MergeIcon tone="outline" />}>
									<Name>Merged</Name> the work was merged
								</Entry>
								<Entry mark={<MergeIcon tone="warning" />}>
									<Name>Merged early</Name> before the review or its feedback was done
								</Entry>
								<Entry mark={<span className="text-muted-foreground">Aa</span>}>
									<Name>Grey words</Name> off the path of your record
								</Entry>
							</LegendGroup>
							<LegendGroup title="Icons">
								<IconRow label="Where it went">
									{DESTINATIONS.map((def) => (
										<StepTag key={def.label} def={def} />
									))}
								</IconRow>
								<IconRow label="Delivery">
									{DELIVERY.map((def) => (
										<IconLabel key={def.label} def={def} />
									))}
								</IconRow>
								<IconRow label="The developer’s answer">
									{ANSWERS.map((def) => (
										<IconLabel key={def.label} def={def} />
									))}
								</IconRow>
							</LegendGroup>
						</div>
					</div>
					<div className="space-y-2 border-t bg-muted/40 px-4 py-3 text-muted-foreground">
						<p>
							<span className="font-medium text-foreground">Terms:</span>{" "}
							{TERMS.map((term, index) => (
								<span key={term}>
									{index > 0 && <DotSeparator inline />}
									<GlossaryTerm term={term}>{TRACE_TERMS[term].label.toLowerCase()}</GlossaryTerm>
								</span>
							))}
						</p>
						<section aria-labelledby={cannotId} className="space-y-0.5">
							<h4 id={cannotId} className="font-medium text-foreground">
								What the trace cannot show
							</h4>
							<ul className="list-disc space-y-0.5 pl-4">
								<li>Whether an observation is right: only an admin’s “Marked incorrect”.</li>
								<li>Why the model judged so: only its rationale is stored.</li>
								<li>Whether the developer read a comment on the work, or learned from it.</li>
							</ul>
						</section>
					</div>
				</div>
			</PopoverContent>
		</Popover>
	);
}

function LegendGroup({ title, children }: { title: string; children: ReactNode }) {
	const id = useId();
	return (
		<section aria-labelledby={id} className="space-y-1.5">
			<h4 id={id} className="text-2xs font-semibold tracking-wide text-muted-foreground uppercase">
				{title}
			</h4>
			<ul className="space-y-1.5">{children}</ul>
		</section>
	);
}

/** One sample: the mark in a column of its own, so every name starts on one edge. */
function Entry({ mark, children }: { mark: ReactNode; children: ReactNode }) {
	return (
		<li className="grid grid-cols-[1.25rem_minmax(0,1fr)] items-center gap-x-2.5">
			<span aria-hidden className="flex min-h-5 items-center justify-center">
				{mark}
			</span>
			<span className="min-w-0 text-muted-foreground">{children}</span>
		</li>
	);
}

/** The name of a mark, with a colon before its one line. */
function Name({ children }: { children: ReactNode }) {
	return (
		<>
			<span className="font-medium text-foreground">{children}</span>:
		</>
	);
}

function IconRow({ label, children }: { label: string; children: ReactNode }) {
	return (
		<li className="space-y-1">
			<span className="block text-muted-foreground">{label}</span>
			<span className="flex flex-wrap items-center gap-x-3 gap-y-1">{children}</span>
		</li>
	);
}

function IconLabel({ def }: { def: StatusDef }) {
	return (
		<span className="inline-flex items-center gap-1">
			<ToneIcon def={def} />
			{def.label}
		</span>
	);
}

function Chip({ className, children }: { className: string; children: ReactNode }) {
	return <span className={className}>{children}</span>;
}
