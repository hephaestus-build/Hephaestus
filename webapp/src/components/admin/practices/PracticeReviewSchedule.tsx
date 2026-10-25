import { Link } from "@tanstack/react-router";
import { format } from "date-fns";
import { AlertCircle, CalendarClock } from "lucide-react";
import { type ReactNode, useId, useState } from "react";

import type { ReviewSchedule, ReviewScheduleInput } from "@/api/types.gen";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { InlineLink } from "@/components/common/InlineLink";
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
	FieldError,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { asDate } from "@/lib/dates";
import { ITEM_KIND, itemKindLabel, itemKindNoun } from "@/lib/item-kinds";
import { formatCapUsd } from "@/lib/money";
import { hasText } from "@/lib/text";

export interface PracticeReviewScheduleProps {
	/** Where its repository scans are listed. */
	workspaceSlug: string;
	schedules: ReviewSchedule[];
	isLoading: boolean;
	isError: boolean;
	onRetry: () => void;
	isSaving: boolean;
	onCreate: (input: ReviewScheduleInput) => void;
	onReplace: (schedule: ReviewSchedule, input: ReviewScheduleInput) => void;
	onSetEnabled: (schedule: ReviewSchedule, enabled: boolean) => void;
	onDelete: (schedule: ReviewSchedule) => void;
}

const ITEM_KINDS = [ITEM_KIND.pullRequest, ITEM_KIND.issue, ITEM_KIND.repository] as const;

const ITEM_KIND_ITEMS = ITEM_KINDS.map((kind) => ({
	value: kind as string,
	label: itemKindLabel(kind, 2),
}));

const scans = (itemKind: string) => itemKind === ITEM_KIND.repository;

type Cadence = ReviewScheduleInput["cadence"];

const CADENCES: { value: Cadence; label: string }[] = [
	{ value: "DAILY", label: "Every day" },
	{ value: "WEEKLY", label: "Every week" },
];

const LOOKBACK_CEILING: Record<Cadence, number> = { DAILY: 2, WEEKLY: 7 };

const lookbackItems = (cadence: Cadence) =>
	Array.from({ length: LOOKBACK_CEILING[cadence] }, (_, index) => {
		const days = index + 1;
		return { value: String(days), label: days === 1 ? "The last day" : `The last ${days} days` };
	});

const BUDGET_PATTERN = /^(?:0|[1-9]\d*)(?:\.\d{1,4})?$/u;

function budgetError(text: string): string | null {
	if (!BUDGET_PATTERN.test(text.trim())) {
		return "Enter an amount in US dollars with at most four decimal places, such as 25 or 12.50.";
	}
	const value = Number(text);
	return value < 0.0001 || value > 100_000 ? "Enter an amount from $0.0001 to $100,000." : null;
}

const describeSchedule = (schedule: ReviewSchedule) => {
	const every = schedule.cadence === "DAILY" ? "Every day" : "Every week";
	if (scans(schedule.itemKind)) {
		const budget =
			schedule.budgetUsd === null ? "no budget" : formatCapUsd(Number(schedule.budgetUsd));
		return `${every}, scanning each repository in the review scope, up to ${budget} per run`;
	}
	const window =
		schedule.lookbackDays === 1 ? "the last day" : `the last ${schedule.lookbackDays ?? 0} days`;
	return `${every}, covering ${window}`;
};

const formatMoment = (value: Date | null) => {
	const date = asDate(value);
	return date ? format(date, "d MMM yyyy, HH:mm") : undefined;
};

export function PracticeReviewSchedule({
	workspaceSlug,
	schedules,
	isLoading,
	isError,
	onRetry,
	isSaving,
	onCreate,
	onReplace,
	onSetEnabled,
	onDelete,
}: PracticeReviewScheduleProps) {
	const scheduledKinds = new Set(schedules.map((schedule) => schedule.itemKind));
	const availableKinds = ITEM_KIND_ITEMS.filter((kind) => !scheduledKinds.has(kind.value));
	// A first read that failed leaves no list to show: the error stands in for it, and "nothing is
	// scheduled" would be a claim the page cannot make.
	const unknown = isError && schedules.length === 0;

	let scheduleList: ReactNode;
	if (unknown) {
		scheduleList = null;
	} else if (isLoading) {
		scheduleList = (
			<div className="space-y-2" aria-hidden>
				<Skeleton className="h-16 w-full" />
				<Skeleton className="h-16 w-full" />
			</div>
		);
	} else if (schedules.length === 0) {
		scheduleList = (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<CalendarClock />
					</EmptyMedia>
					<EmptyTitle>Nothing is checked on a schedule</EmptyTitle>
					<EmptyDescription>
						Work that never raised a notification is never reviewed, and nothing says so. Add a
						check below to review the last few days again, or to scan repositories regularly.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	} else {
		scheduleList = (
			<div className="space-y-2">
				{schedules.map((schedule) => (
					<ScheduleRow
						key={schedule.id}
						schedule={schedule}
						isSaving={isSaving}
						onReplace={onReplace}
						onSetEnabled={onSetEnabled}
						onDelete={onDelete}
					/>
				))}
			</div>
		);
	}

	return (
		<section className="space-y-4" aria-labelledby="schedule-heading">
			<div className="space-y-1">
				<h2 id="schedule-heading" className="text-lg font-semibold">
					Keep checking new work
				</h2>
				<p className="text-sm text-muted-foreground">
					Reviews normally start the moment work happens. When a notification is lost, nothing ever
					arrives, and nothing records the review that did not happen. A recurring check looks again
					over the last few days, so anything missed still gets reviewed. A repository schedule
					scans each repository instead. Scans never send feedback to developers.
				</p>
				<p className="text-sm">
					<InlineLink
						render={
							<Link to="/w/$workspaceSlug/admin/practices/scans" params={{ workspaceSlug }} />
						}
					>
						View scans
					</InlineLink>
				</p>
			</div>
			{isError ? (
				<Alert variant="destructive">
					<AlertCircle />
					<AlertTitle>We could not load recurring checks</AlertTitle>
					<AlertDescription>
						<p>Scheduled checks keep running. Check your connection, then try again.</p>
						<Button variant="outline" size="sm" onClick={onRetry}>
							Retry
						</Button>
					</AlertDescription>
				</Alert>
			) : null}

			{scheduleList}

			{availableKinds.length > 0 ? (
				<section className="space-y-4 border-t pt-4" aria-labelledby="schedule-add-heading">
					<h3 id="schedule-add-heading" className="text-sm font-medium">
						Add a recurring check
					</h3>
					<ScheduleForm
						kinds={availableKinds}
						initial={null}
						isSaving={isSaving}
						disabled={isLoading || unknown}
						onSubmit={onCreate}
					/>
				</section>
			) : null}
		</section>
	);
}

function ScheduleRow({
	schedule,
	isSaving,
	onReplace,
	onSetEnabled,
	onDelete,
}: {
	schedule: ReviewSchedule;
	isSaving: boolean;
	onReplace: PracticeReviewScheduleProps["onReplace"];
	onSetEnabled: PracticeReviewScheduleProps["onSetEnabled"];
	onDelete: PracticeReviewScheduleProps["onDelete"];
}) {
	const [editing, setEditing] = useState(false);
	const [deleting, setDeleting] = useState<ReviewSchedule | null>(null);
	const kind = itemKindLabel(schedule.itemKind, 2);
	const kindNoun = itemKindNoun(schedule.itemKind, 2);
	const nextRun = formatMoment(schedule.nextRunAt);
	const lastRun = formatMoment(schedule.lastRunAt);

	let nextCheck = "Paused, so nothing is being checked.";
	if (schedule.enabled) {
		nextCheck = hasText(nextRun)
			? `Next check ${nextRun}.`
			: "The first check happens within the hour.";
	}
	const description = [
		`${describeSchedule(schedule)}.`,
		nextCheck,
		hasText(lastRun) ? `Last checked ${lastRun}.` : "It has not checked anything yet.",
	].join(" ");

	return (
		<Item variant="outline" className="flex-wrap">
			<ItemContent>
				<ItemTitle>{kind}</ItemTitle>
				<ItemDescription>{description}</ItemDescription>
			</ItemContent>
			<ItemActions className="flex-wrap">
				<Badge variant={schedule.enabled ? "secondary" : "outline"}>
					{schedule.enabled ? "On" : "Paused"}
				</Badge>
				{/* One row per kind, so every row would otherwise answer to the same word. The visible word
				    still opens the name a voice-control user says (WCAG 2.2 SC 2.5.3). */}
				<Button
					variant="outline"
					size="sm"
					disabled={isSaving}
					onClick={() => onSetEnabled(schedule, !schedule.enabled)}
				>
					{schedule.enabled ? "Pause" : "Resume"}{" "}
					<span className="sr-only">checking {kindNoun}</span>
				</Button>
				<Button
					variant="outline"
					size="sm"
					disabled={isSaving}
					aria-expanded={editing}
					onClick={() => setEditing((open) => !open)}
				>
					Edit <span className="sr-only">the recurring check on {kindNoun}</span>
				</Button>
				<Button variant="ghost" size="sm" disabled={isSaving} onClick={() => setDeleting(schedule)}>
					Delete <span className="sr-only">the recurring check on {kindNoun}</span>
				</Button>
			</ItemActions>
			{/* A confirm, not Undo: the API cannot restore a deleted schedule. */}
			<ConfirmDialog
				subject={deleting}
				title={() => `Delete the recurring check on ${kindNoun}?`}
				description={(shown) =>
					shown.enabled
						? "This deletes the schedule and its settings. You cannot undo this. To stop checking for a while, pause it instead."
						: "This deletes the schedule and its settings. You cannot undo this."
				}
				confirmLabel="Delete recurring check"
				onConfirm={onDelete}
				onClose={() => setDeleting(null)}
			/>
			{editing ? (
				<div className="w-full border-t pt-4">
					<ScheduleForm
						key={schedule.version}
						kinds={[{ value: schedule.itemKind, label: kind }]}
						initial={schedule}
						isSaving={isSaving}
						disabled={false}
						onSubmit={(input) => {
							onReplace(schedule, input);
							setEditing(false);
						}}
						onCancel={() => setEditing(false)}
					/>
				</div>
			) : null}
		</Item>
	);
}

function ScheduleForm({
	kinds,
	initial,
	isSaving,
	disabled,
	onSubmit,
	onCancel,
}: {
	kinds: { value: string; label: string }[];
	initial: ReviewSchedule | null;
	isSaving: boolean;
	disabled: boolean;
	onSubmit: (input: ReviewScheduleInput) => void;
	onCancel?: () => void;
}) {
	const id = useId();
	const [itemKind, setItemKind] = useState(initial?.itemKind ?? kinds[0]?.value ?? "");
	const [cadence, setCadence] = useState<Cadence>(initial?.cadence ?? "DAILY");
	const [lookbackDays, setLookbackDays] = useState(String(initial?.lookbackDays ?? 2));
	const [budget, setBudget] = useState(initial?.budgetUsd ?? "");
	const [enabled, setEnabled] = useState(initial?.enabled ?? true);
	const [budgetTouched, setBudgetTouched] = useState(false);

	const scan = scans(itemKind);
	const budgetProblem = scan ? budgetError(budget) : null;
	const showBudgetError = budgetTouched && budgetProblem !== null;
	const chosenKind = itemKindNoun(itemKind, 2);

	// A tighter cadence can invalidate the window; reset it to a server-accepted value.
	const changeCadence = (next: Cadence) => {
		setCadence(next);
		setLookbackDays(String(LOOKBACK_CEILING[next]));
	};

	const windowItems = lookbackItems(cadence);
	const submit = () => {
		if (budgetProblem !== null) {
			setBudgetTouched(true);
			return;
		}
		onSubmit({
			itemKind,
			cadence,
			lookbackDays: scan ? null : Number(lookbackDays),
			budgetUsd: scan ? budget.trim() : null,
			enabled,
		});
	};

	return (
		<div className="space-y-4">
			{/* `FieldGroup` is what `responsive` measures: it opens the `@container/field-group` the
			    orientation's `@md` breakpoint reads. Without it the row is horizontal at every width, and
			    at 320px a 14rem control leaves the label about 60px. */}
			<FieldGroup>
				{initial === null ? (
					<Field orientation="responsive">
						<FieldContent>
							<FieldLabel id={`${id}-kind-label`} htmlFor={`${id}-kind`}>
								Kind of work
							</FieldLabel>
							<FieldDescription>One schedule per kind.</FieldDescription>
						</FieldContent>
						<Select
							items={kinds}
							value={itemKind}
							onValueChange={(value) => setItemKind(value ?? itemKind)}
						>
							<SelectTrigger id={`${id}-kind`} className="w-full @md/field-group:w-56">
								<SelectValue />
							</SelectTrigger>
							<SelectContent aria-labelledby={`${id}-kind-label`}>
								{kinds.map((kind) => (
									<SelectItem key={kind.value} value={kind.value}>
										{kind.label}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
					</Field>
				) : null}

				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel id={`${id}-cadence-label`} htmlFor={`${id}-cadence`}>
							How often
						</FieldLabel>
						<FieldDescription>Runs on its own. There is nothing to start by hand.</FieldDescription>
					</FieldContent>
					<Select
						items={CADENCES}
						value={cadence}
						onValueChange={(value) => changeCadence(value ?? cadence)}
					>
						<SelectTrigger id={`${id}-cadence`} className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby={`${id}-cadence-label`}>
							{CADENCES.map((option) => (
								<SelectItem key={option.value} value={option.value}>
									{option.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>

				{scan ? (
					<Field orientation="responsive" data-invalid={showBudgetError || undefined}>
						<FieldContent>
							<FieldLabel htmlFor={`${id}-budget`}>Budget per run (USD)</FieldLabel>
							<FieldDescription id={`${id}-budget-description`}>
								Each run scans the repositories in the review scope one at a time and stops at this
								amount.
							</FieldDescription>
							{showBudgetError ? (
								<FieldError id={`${id}-budget-error`}>{budgetProblem}</FieldError>
							) : null}
						</FieldContent>
						<Input
							id={`${id}-budget`}
							name="schedule-budget"
							inputMode="decimal"
							autoComplete="off"
							placeholder="25"
							value={budget}
							aria-invalid={showBudgetError || undefined}
							aria-describedby={
								showBudgetError
									? `${id}-budget-description ${id}-budget-error`
									: `${id}-budget-description`
							}
							onChange={(event) => setBudget(event.currentTarget.value)}
							onBlur={() => setBudgetTouched(true)}
							className="w-full @md/field-group:w-56"
						/>
					</Field>
				) : (
					<Field orientation="responsive">
						<FieldContent>
							<FieldLabel id={`${id}-window-label`} htmlFor={`${id}-window`}>
								How far back each time
							</FieldLabel>
							<FieldDescription>
								Overlapping on purpose: work missed once still gets a second chance, and work
								already reviewed is never paid for twice.
							</FieldDescription>
						</FieldContent>
						<Select
							items={windowItems}
							value={lookbackDays}
							onValueChange={(value) => setLookbackDays(value ?? lookbackDays)}
						>
							<SelectTrigger id={`${id}-window`} className="w-full @md/field-group:w-56">
								<SelectValue />
							</SelectTrigger>
							<SelectContent aria-labelledby={`${id}-window-label`}>
								{windowItems.map((option) => (
									<SelectItem key={option.value} value={option.value}>
										{option.label}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
					</Field>
				)}

				{initial === null ? null : (
					<Field orientation="horizontal">
						<FieldContent>
							<FieldLabel htmlFor={`${id}-enabled`}>Keep checking</FieldLabel>
							<FieldDescription>
								Turn this off to pause the schedule without losing it.
							</FieldDescription>
						</FieldContent>
						<Switch id={`${id}-enabled`} checked={enabled} onCheckedChange={setEnabled} />
					</Field>
				)}
			</FieldGroup>

			<div className="flex flex-wrap items-center gap-3">
				<Button disabled={isSaving || disabled} onClick={submit}>
					{isSaving ? <Spinner /> : null}
					{initial === null ? `Start checking ${chosenKind}` : "Save schedule"}
				</Button>
				{onCancel ? (
					<Button variant="outline" disabled={isSaving} onClick={onCancel}>
						Cancel
					</Button>
				) : null}
				<p className="text-sm text-muted-foreground">
					Every check can start reviews, so this authorizes the AI spend for all of them, not just
					the first.
				</p>
			</div>
		</div>
	);
}
