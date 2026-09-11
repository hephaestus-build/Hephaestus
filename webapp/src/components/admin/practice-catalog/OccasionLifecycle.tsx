import { cn } from "cn";
import type { PracticeSignalOption, PracticeWorkTypeDefinitionOptions } from "@/api/types.gen";
import {
	hasDrafts,
	OCCASION_ID_PREFIX,
	occasionFieldId,
} from "@/components/admin/practice-catalog/bindings";
import {
	momentBands,
	momentDef,
	PHASE_LABEL,
	withdrawnMoments,
} from "@/components/admin/practice-catalog/occasion-moments";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";

export interface OccasionLifecycleProps {
	/** The whole work type, not its `signals`: the artifact kind decides whether drafts can occur. */
	workType: PracticeWorkTypeDefinitionOptions;
	selected: readonly string[];
	onToggle: (signal: string, chosen: boolean) => void;
	/**
	 * Named for what it is rather than for the wire field it ends up in (`PracticeBinding.onDrafts`):
	 * `on*` is a callback everywhere else in this kit, so `onDrafts` beside `onDraftsChange` read as
	 * two handlers at the only call site.
	 */
	includeDrafts: boolean;
	onIncludeDraftsChange: (includeDrafts: boolean) => void;
	/**
	 * The id of the message describing what is wrong, passed only while the moments are what fails
	 * validation; its presence is also what draws them in the invalid state. One field rather than an
	 * `invalid` flag beside an id, so neither can be set without the other.
	 */
	errorId?: string;
	disabled?: boolean;
}

/**
 * When a practice is reviewed, drawn as the life of the work rather than as a list of checkboxes.
 * Bands come from the moments the work type offers, so every kind renders from the same code.
 */
export function OccasionLifecycle({
	workType,
	selected,
	onToggle,
	includeDrafts,
	onIncludeDraftsChange,
	errorId,
	disabled = false,
}: OccasionLifecycleProps) {
	const invalid = errorId !== undefined;
	const draftsId = `${OCCASION_ID_PREFIX}-on-drafts`;
	const draftsHintId = `${draftsId}-hint`;
	const chosen = new Set(selected);
	const bands = momentBands([...workType.signals, ...withdrawnMoments(workType, selected)]);

	return (
		<FieldSet
			id={occasionFieldId("signals")}
			data-invalid={invalid || undefined}
			aria-describedby={errorId}
			// Focusable only programmatically: a form-level error sends focus here so the author lands on
			// the strip it names, but the group stays out of the tab order.
			tabIndex={-1}
			aria-label="Reviews when"
		>
			<FieldLegend variant="label">Reviews when *</FieldLegend>
			<div className="flex flex-wrap items-start gap-x-6 gap-y-4">
				{bands.map((band) => {
					// No rail inside "Ends": merged and closed without merging are alternatives, and a line
					// between them would say a pull request is merged and then closed.
					const railed = band.phase !== "end";
					const rail = (
						<div className="flex items-start">
							{band.moments.map((moment) => (
								<MomentNode
									key={moment.signal}
									moment={moment}
									railed={railed}
									chosen={chosen.has(moment.signal)}
									controlId={`${OCCASION_ID_PREFIX}-signal-${moment.signal}`}
									disabled={disabled}
									invalid={invalid}
									onToggle={(next) => onToggle(moment.signal, next)}
								/>
							))}
						</div>
					);
					// A single band has nothing to tell apart, so no heading — and so nothing to name a
					// group by.
					if (bands.length === 1) return <div key={band.phase}>{rail}</div>;
					// Named groups rather than loose headings: a reader who cannot see the bands would meet
					// one run of checkboxes with stray words between them.
					const headingId = `${OCCASION_ID_PREFIX}-band-${band.phase}`;
					return (
						<div key={band.phase} role="group" aria-labelledby={headingId}>
							<p
								id={headingId}
								className="mb-1.5 pl-1 text-[0.7rem] font-medium uppercase tracking-wide text-muted-foreground"
							>
								{PHASE_LABEL[band.phase]}
							</p>
							{rail}
						</div>
					);
				})}
			</div>

			{hasDrafts(workType.artifactKind) && (
				<Field orientation="horizontal" className="mt-1 gap-3" data-disabled={disabled}>
					<Switch
						id={draftsId}
						disabled={disabled}
						checked={includeDrafts}
						onCheckedChange={onIncludeDraftsChange}
						aria-describedby={draftsHintId}
					/>
					{/* Label and description are siblings, never nested: a `<label>` takes phrasing content
					    only, and a description inside it joins the switch's accessible name. A name is what
					    the control *is*; the sentence explaining the default is `aria-describedby`. */}
					<FieldContent>
						<FieldLabel htmlFor={draftsId} className="font-normal">
							Include drafts
						</FieldLabel>
						<FieldDescription id={draftsHintId}>
							Off by default: read the work once it is offered as finished.
						</FieldDescription>
					</FieldContent>
				</Field>
			)}
		</FieldSet>
	);
}

interface MomentNodeProps {
	moment: PracticeSignalOption;
	/** Draw the hairline reaching back to the moment before it, where the band is a progression. */
	railed: boolean;
	chosen: boolean;
	controlId: string;
	disabled: boolean;
	invalid: boolean;
	onToggle: (chosen: boolean) => void;
}

/**
 * One point on the strip: a real checkbox with a label, drawn as a node on a rail. Nothing here
 * reimplements checkbox behaviour — `htmlFor` leaves the click, the Space press and the accessible
 * name to the platform, and only the focus ring is moved onto the node a reader actually sees.
 */
function MomentNode({
	moment,
	railed,
	chosen,
	controlId,
	disabled,
	invalid,
	onToggle,
}: MomentNodeProps) {
	const def = momentDef(moment.signal);
	const Icon = def.icon;
	return (
		<label
			htmlFor={controlId}
			className={cn(
				"group/moment relative flex w-24 shrink-0 flex-col items-center gap-1 px-1 pt-1 pb-0.5 text-center",
				// The rail: a hairline spanning circle edge to circle edge. Every node is the same width
				// with its circle centred, so -50% of the node plus half a circle lands on the previous
				// one. Hidden on the first node of a band, so the line never dangles.
				railed &&
					"before:absolute before:top-5 before:-left-1/2 before:right-1/2 before:mr-4 before:ml-4 before:h-px before:bg-border first:before:hidden",
				"rounded-md has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50",
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
						"group-hover/moment:border-primary/60 group-hover/moment:text-foreground",
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
				{moment.displayName}
			</span>
			{def.repeats && (
				<span className="text-[0.65rem] leading-none text-muted-foreground">every time</span>
			)}
		</label>
	);
}
