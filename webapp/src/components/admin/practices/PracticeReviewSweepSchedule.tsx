import { format } from "date-fns";
import { AlertCircle, CalendarClock } from "lucide-react";
import { useState } from "react";

import type {
	CreateReviewSweepScheduleRequest,
	ReviewSweepSchedule,
	UpdateReviewSweepScheduleRequest,
} from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { ARTIFACT_KIND, artifactKindPluralLabel } from "@/lib/artifact-kinds";
import { asDate } from "@/lib/dates";

export interface PracticeReviewSweepScheduleProps {
	schedules: ReviewSweepSchedule[];
	isLoading: boolean;
	isError: boolean;
	onRetry: () => void;
	isSaving: boolean;
	onCreate: (request: CreateReviewSweepScheduleRequest) => void;
	onReplace: (scheduleId: string, request: UpdateReviewSweepScheduleRequest) => void;
	onDelete: (scheduleId: string) => void;
}

/** The kinds a campaign can enumerate, which is what a sweep opens one of. */
type WorkKind = CreateReviewSweepScheduleRequest["artifactKind"];

const WORK_KINDS = [
	ARTIFACT_KIND.pullRequest,
	ARTIFACT_KIND.issue,
] as const satisfies readonly WorkKind[];

const WORK_KIND_ITEMS: { value: WorkKind; label: string }[] = WORK_KINDS.map((kind) => ({
	value: kind,
	label: artifactKindPluralLabel(kind),
}));

type Cadence = CreateReviewSweepScheduleRequest["cadence"];

const CADENCES: { value: Cadence; label: string }[] = [
	{ value: "DAILY", label: "Every day" },
	{ value: "WEEKLY", label: "Every week" },
];

/**
 * The same ceiling the server enforces, offered here so an admin picks from what is allowed instead
 * of discovering the rule through a 400. A sweep's observations are counted alongside reviews the
 * work itself triggered, and that only holds while its window is "the last few days" — anything
 * longer is a stretch of history somebody chose, which the backfill is for.
 */
const LOOKBACK_CEILING: Record<Cadence, number> = { DAILY: 2, WEEKLY: 7 };

const lookbackItems = (cadence: Cadence) =>
	Array.from({ length: LOOKBACK_CEILING[cadence] }, (_, index) => {
		const days = index + 1;
		return { value: String(days), label: days === 1 ? "The last day" : `The last ${days} days` };
	});

const describeCadence = (schedule: ReviewSweepSchedule) => {
	const every = schedule.cadence === "DAILY" ? "Every day" : "Every week";
	const window =
		schedule.lookbackDays === 1 ? "the last day" : `the last ${schedule.lookbackDays} days`;
	return `${every}, covering ${window}`;
};

/**
 * `nextRunAt`/`lastRunAt` are typed `Date` but arrive as ISO strings, so they go through `asDate`.
 * `RelativeTime` does not fit: it renders a tooltip trigger, and this is one clause of a plain
 * sentence.
 */
const formatMoment = (value: Date | undefined) => {
	const date = asDate(value);
	return date ? format(date, "d MMM yyyy, HH:mm") : undefined;
};

export function PracticeReviewSweepSchedule({
	schedules,
	isLoading,
	isError,
	onRetry,
	isSaving,
	onCreate,
	onReplace,
	onDelete,
}: PracticeReviewSweepScheduleProps) {
	const scheduledKinds = new Set(schedules.map((schedule) => schedule.artifactKind));
	const availableKinds = WORK_KIND_ITEMS.filter((kind) => !scheduledKinds.has(kind.value));

	return (
		<section className="space-y-4" aria-labelledby="sweep-heading">
			<div className="space-y-1">
				<h2 id="sweep-heading" className="text-lg font-semibold">
					Keep checking new work
				</h2>
				<p className="text-sm text-muted-foreground">
					Reviews normally start the moment work happens. When a notification is lost, nothing ever
					arrives — and there is no record of the review that did not happen. A recurring check
					looks again over the last few days, so anything missed still gets reviewed.
				</p>
			</div>
			{isError ? (
				<Alert variant="destructive">
					<AlertCircle />
					<AlertTitle>Recurring checks couldn't be loaded</AlertTitle>
					<AlertDescription>
						<p>Whatever is scheduled is still running — this is only about showing it here.</p>
						<Button variant="outline" size="sm" onClick={onRetry}>
							Try again
						</Button>
					</AlertDescription>
				</Alert>
			) : null}

			{isLoading ? (
				<div className="flex justify-center py-6">
					<Spinner />
				</div>
			) : schedules.length === 0 ? (
				<Empty>
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<CalendarClock />
						</EmptyMedia>
						<EmptyTitle>Nothing is checked on a schedule</EmptyTitle>
						<EmptyDescription>
							Work that never raised a notification is never reviewed, and nothing says so. Add a
							check below to review the last few days again.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			) : (
				<div className="space-y-2">
					{schedules.map((schedule) => (
						<ScheduleRow
							key={schedule.id}
							schedule={schedule}
							isSaving={isSaving}
							onReplace={onReplace}
							onDelete={onDelete}
						/>
					))}
				</div>
			)}

			{availableKinds.length > 0 ? (
				<AddScheduleForm
					availableKinds={availableKinds}
					isSaving={isSaving}
					isLoading={isLoading}
					onCreate={onCreate}
				/>
			) : null}
		</section>
	);
}

function ScheduleRow({
	schedule,
	isSaving,
	onReplace,
	onDelete,
}: {
	schedule: ReviewSweepSchedule;
	isSaving: boolean;
	onReplace: (scheduleId: string, request: UpdateReviewSweepScheduleRequest) => void;
	onDelete: (scheduleId: string) => void;
}) {
	const kind = artifactKindPluralLabel(schedule.artifactKind);
	const nextRun = formatMoment(schedule.nextRunAt);
	const lastRun = formatMoment(schedule.lastRunAt);

	// A paused schedule must not promise a first check "within the hour".
	const description = [
		`${describeCadence(schedule)}.`,
		schedule.enabled
			? nextRun
				? `Next check ${nextRun}.`
				: "The first check happens within the hour."
			: "Paused, so nothing is being checked.",
		lastRun ? `Last checked ${lastRun}.` : "It has not checked anything yet.",
	].join(" ");

	return (
		<Item variant="outline">
			<ItemContent>
				<ItemTitle>{kind}</ItemTitle>
				<ItemDescription>{description}</ItemDescription>
			</ItemContent>
			<ItemActions>
				<Badge variant={schedule.enabled ? "secondary" : "outline"}>
					{schedule.enabled ? "On" : "Paused"}
				</Badge>
				{/* One row per kind, so every row would otherwise answer to the same word. The visible word
				    still opens the name a voice-control user says (WCAG 2.2 SC 2.5.3). */}
				<Button
					variant="outline"
					size="sm"
					disabled={isSaving}
					onClick={() =>
						onReplace(schedule.id, {
							cadence: schedule.cadence,
							lookbackDays: schedule.lookbackDays,
							enabled: !schedule.enabled,
						})
					}
				>
					{schedule.enabled ? "Pause" : "Resume"}
					<span className="sr-only"> checking {kind.toLowerCase()}</span>
				</Button>
				<Button variant="ghost" size="sm" disabled={isSaving} onClick={() => onDelete(schedule.id)}>
					Remove
					<span className="sr-only"> the recurring check on {kind.toLowerCase()}</span>
				</Button>
			</ItemActions>
		</Item>
	);
}

function AddScheduleForm({
	availableKinds,
	isSaving,
	isLoading,
	onCreate,
}: {
	availableKinds: { value: WorkKind; label: string }[];
	isSaving: boolean;
	isLoading: boolean;
	onCreate: (request: CreateReviewSweepScheduleRequest) => void;
}) {
	const firstKind = availableKinds[0]?.value ?? ARTIFACT_KIND.pullRequest;
	const [artifactKind, setArtifactKind] = useState(firstKind);
	const [cadence, setCadence] = useState<Cadence>("DAILY");
	const [lookbackDays, setLookbackDays] = useState("2");

	// Switching to a tighter cadence can invalidate the chosen window, so the cadence owns it rather
	// than leaving a value the server would refuse.
	const changeCadence = (next: Cadence) => {
		setCadence(next);
		setLookbackDays(String(LOOKBACK_CEILING[next]));
	};

	const kindItems = availableKinds;
	const windowItems = lookbackItems(cadence);
	const chosenKind = (
		availableKinds.find((kind) => kind.value === artifactKind)?.label ??
		artifactKindPluralLabel(artifactKind)
	).toLowerCase();

	return (
		// A named group rather than three loose controls after a list: without it a screen-reader user
		// arrives at a second "Kind of work" with nothing saying they have left the schedules behind.
		<section className="space-y-4 border-t pt-4" aria-labelledby="sweep-add-heading">
			<h3 id="sweep-add-heading" className="text-sm font-medium">
				Add a recurring check
			</h3>
			{/* `FieldGroup` is what `responsive` measures: it opens the `@container/field-group` the
			    orientation's `@md` breakpoint reads. Without it the row is horizontal at every width, and
			    at 320px a 14rem control leaves the label about 60px. */}
			<FieldGroup>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel id="sweep-kind-label" htmlFor="sweep-kind">
							Kind of work
						</FieldLabel>
						<FieldDescription>One schedule per kind.</FieldDescription>
					</FieldContent>
					<Select
						items={kindItems}
						value={artifactKind}
						onValueChange={(value) => setArtifactKind(value ?? artifactKind)}
					>
						<SelectTrigger id="sweep-kind" className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby="sweep-kind-label">
							{kindItems.map((kind) => (
								<SelectItem key={kind.value} value={kind.value}>
									{kind.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>

				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel id="sweep-cadence-label" htmlFor="sweep-cadence">
							How often
						</FieldLabel>
						<FieldDescription>Runs on its own; there is nothing to start by hand.</FieldDescription>
					</FieldContent>
					<Select
						items={CADENCES}
						value={cadence}
						onValueChange={(value) => changeCadence(value ?? cadence)}
					>
						<SelectTrigger id="sweep-cadence" className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby="sweep-cadence-label">
							{CADENCES.map((option) => (
								<SelectItem key={option.value} value={option.value}>
									{option.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>

				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel id="sweep-window-label" htmlFor="sweep-window">
							How far back each time
						</FieldLabel>
						<FieldDescription>
							Overlapping on purpose: work missed once still gets a second chance, and work already
							reviewed is never paid for twice.
						</FieldDescription>
					</FieldContent>
					<Select
						items={windowItems}
						value={lookbackDays}
						onValueChange={(value) => setLookbackDays(value ?? lookbackDays)}
					>
						<SelectTrigger id="sweep-window" className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby="sweep-window-label">
							{windowItems.map((option) => (
								<SelectItem key={option.value} value={option.value}>
									{option.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>
			</FieldGroup>

			<div className="flex flex-wrap items-center gap-3">
				<Button
					disabled={isSaving || isLoading}
					onClick={() =>
						onCreate({
							artifactKind,
							cadence,
							lookbackDays: Number(lookbackDays),
						})
					}
				>
					{isSaving ? <Spinner /> : null}
					Start checking {chosenKind}
				</Button>
				<p className="text-sm text-muted-foreground">
					Every check can start reviews, so this authorises the AI spend for all of them — not just
					the first.
				</p>
			</div>
		</section>
	);
}
