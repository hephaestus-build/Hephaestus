import { cn } from "cn";
import type { ItemEventOption, PracticeItemTypeOption, ReviewOn } from "@/api/types.gen";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldDescription, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";

import { eventBands, eventDef, PHASE_LABEL, withdrawnEvents } from "./review-events";
import { normalizeReviewOn, REVIEW_ON_ID_PREFIX, reviewOnFieldId } from "./review-settings";

export interface ReviewOnEditorProps {
	itemType: PracticeItemTypeOption;
	reviewOn: ReviewOn;
	onChange: (reviewOn: ReviewOn) => void;
	eventsErrorId?: string;
	stateErrorId?: string;
	disabled?: boolean;
}

export function ReviewOnEditor({
	itemType,
	reviewOn,
	onChange,
	eventsErrorId,
	stateErrorId,
	disabled = false,
}: ReviewOnEditorProps) {
	const invalid = eventsErrorId !== undefined;
	const chosen = new Set(reviewOn.events);
	const bands = eventBands([...itemType.events, ...withdrawnEvents(itemType, reviewOn.events)]);
	const toggle = (name: string, next: boolean) =>
		onChange(
			normalizeReviewOn({
				...reviewOn,
				events: next
					? [...reviewOn.events, name]
					: reviewOn.events.filter((value) => value !== name),
			}),
		);

	return (
		<div className="space-y-4">
			<FieldSet
				id={reviewOnFieldId("events")}
				data-invalid={invalid || undefined}
				aria-describedby={eventsErrorId}
				// Focusable only programmatically: a form-level error sends focus here so the author lands on
				// the strip it names, but the group stays out of the tab order.
				tabIndex={-1}
				aria-label="Review on"
			>
				<FieldLegend variant="label">Review on</FieldLegend>
				{bands.length === 0 ? (
					<FieldDescription>
						This kind of work has no events. It is reviewed when someone requests a review.
					</FieldDescription>
				) : (
					<div className="flex flex-wrap items-start gap-x-6 gap-y-4">
						{bands.map((band) => {
							// No rail inside "Ends": merged and closed without merging are alternatives, and a
							// line between them would say a pull request is merged and then closed.
							const railed = band.phase !== "end";
							const rail = (
								<div className="flex items-start">
									{band.events.map((option) => (
										<EventNode
											key={option.name}
											option={option}
											railed={railed}
											chosen={chosen.has(option.name)}
											controlId={`${REVIEW_ON_ID_PREFIX}-event-${option.name}`}
											disabled={disabled}
											invalid={invalid}
											onToggle={(next) => toggle(option.name, next)}
										/>
									))}
								</div>
							);
							if (bands.length === 1) {
								return <div key={band.phase}>{rail}</div>;
							}
							// Named groups rather than loose headings: a reader who cannot see the bands would
							// meet one run of checkboxes with stray words between them.
							const headingId = `${REVIEW_ON_ID_PREFIX}-band-${band.phase}`;
							return (
								<div key={band.phase} role="group" aria-labelledby={headingId}>
									<p
										id={headingId}
										className="mb-1.5 pl-1 text-2xs font-medium tracking-wide text-muted-foreground uppercase"
									>
										{PHASE_LABEL[band.phase]}
									</p>
									{rail}
								</div>
							);
						})}
					</div>
				)}
				{bands.length > 0 && reviewOn.events.length === 0 && (
					<FieldDescription>
						With no event chosen, this practice is reviewed only when someone requests a review.
					</FieldDescription>
				)}
			</FieldSet>
			{itemType.reviewOnStateDimensions.length > 0 && (
				<FieldSet
					id={reviewOnFieldId("state")}
					disabled={disabled}
					aria-describedby={stateErrorId}
					data-invalid={stateErrorId !== undefined || undefined}
					tabIndex={-1}
				>
					<FieldLegend variant="label">When</FieldLegend>
					<FieldDescription>
						Review only work in these states. A requested review ignores them.
					</FieldDescription>
					{itemType.reviewOnStateDimensions.map((dimension) => {
						const options = dimension.values.flatMap(({ value, label }) =>
							value === undefined ? [] : [{ value, label: label ?? value }],
						);
						const selectedValues =
							reviewOn.state[dimension.name] ?? options.map(({ value }) => value);
						return (
							<FieldSet key={dimension.name} className="gap-2">
								<FieldLegend variant="label" className="text-muted-foreground">
									{dimension.label}
								</FieldLegend>
								{options.map((option) => {
									const controlId = `${REVIEW_ON_ID_PREFIX}-${dimension.name}-${option.value}`;
									return (
										<Field key={option.value} orientation="horizontal">
											<Checkbox
												id={controlId}
												checked={selectedValues.includes(option.value)}
												disabled={
													disabled ||
													(selectedValues.length === 1 && selectedValues.includes(option.value))
												}
												onCheckedChange={(checked) => {
													const values = checked
														? [...selectedValues, option.value]
														: selectedValues.filter((value) => value !== option.value);
													const state = Object.fromEntries(
														Object.entries(reviewOn.state).filter(
															([name]) => name !== dimension.name,
														),
													);
													if (values.length < options.length) {
														state[dimension.name] = values;
													}
													onChange(normalizeReviewOn({ ...reviewOn, state }));
												}}
											/>
											<FieldLabel htmlFor={controlId}>{option.label}</FieldLabel>
										</Field>
									);
								})}
							</FieldSet>
						);
					})}
				</FieldSet>
			)}
		</div>
	);
}

interface EventNodeProps {
	option: ItemEventOption;
	railed: boolean;
	chosen: boolean;
	controlId: string;
	disabled: boolean;
	invalid: boolean;
	onToggle: (chosen: boolean) => void;
}

function EventNode({
	option,
	railed,
	chosen,
	controlId,
	disabled,
	invalid,
	onToggle,
}: EventNodeProps) {
	const def = eventDef(option.name);
	const Icon = def.icon;
	return (
		<label
			htmlFor={controlId}
			className={cn(
				"group/event relative flex w-24 shrink-0 flex-col items-center gap-1 px-1 pt-1 pb-0.5 text-center",
				// The rail: a hairline spanning circle edge to circle edge. Every node is the same width
				// with its circle centred, so -50% of the node plus half a circle lands on the previous
				// one. Hidden on the first node of a band, so the line never dangles.
				railed &&
					"before:absolute before:top-5 before:right-1/2 before:-left-1/2 before:mr-4 before:ml-4 before:h-px before:bg-border first:before:hidden",
				// `FOCUS_RING` keys on the focused element itself; here the focus is on the hidden checkbox
				// inside, so the label spells the same ring with `has-[:focus-visible]`.
				"rounded-md has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-ring/50 has-[:focus-visible]:outline-1 has-[:focus-visible]:outline-ring",
				disabled ? "cursor-not-allowed" : "cursor-pointer",
			)}
		>
			{/* Clipped, not hidden: the control stays in the accessibility tree and stays focusable. */}
			<span className="sr-only">
				<Checkbox
					id={controlId}
					checked={chosen}
					disabled={disabled}
					onCheckedChange={(next) => onToggle(next)}
				/>
			</span>
			<span
				className={cn(
					"grid size-8 shrink-0 place-items-center rounded-full border transition-colors",
					chosen && "border-primary bg-primary text-primary-foreground",
					!chosen && "border-border bg-background text-muted-foreground",
					!chosen && invalid && "border-destructive/60",
					!chosen &&
						!disabled &&
						"group-hover/event:border-primary/60 group-hover/event:text-foreground",
					disabled && "opacity-70",
				)}
			>
				<Icon className="size-4" aria-hidden />
			</span>
			<span
				className={cn(
					"text-xs leading-tight",
					chosen ? "font-medium text-foreground" : "text-muted-foreground",
					disabled && "opacity-70",
				)}
			>
				{option.label}
			</span>
			{def.repeats && (
				<span className="text-2xs leading-none text-muted-foreground">every time</span>
			)}
		</label>
	);
}
