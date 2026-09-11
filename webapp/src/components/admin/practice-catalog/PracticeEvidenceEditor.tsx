import deepEqual from "fast-deep-equal";
import { ChevronRightIcon, RotateCcwIcon } from "lucide-react";
import { useState } from "react";

import { cn } from "cn";
import type {
	PracticeEvidenceRequirement,
	PracticeEvidenceSourceOption,
	PracticeWorkTypeDefinitionOptions,
} from "@/api/types.gen";
import { type EvidenceRole, roleOf, withRole } from "@/components/admin/practice-catalog/bindings";
import {
	evidenceQualityRequirement,
	groupEvidenceSources,
} from "@/components/admin/practice-catalog/evidence-presentation";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";

/**
 * EXHAUSTIVE is deliberately not a segment here: it is REQUIRED plus one further claim, and it is
 * offered as that claim — see {@link AbsenceClaim}.
 */
const EVIDENCE_ROLE_OPTIONS = [
	{
		value: "REQUIRED",
		label: "Required",
		selected: "bg-primary text-primary-foreground hover:bg-primary",
	},
	{
		value: "CONTEXTUAL",
		label: "Context",
		selected: "bg-secondary text-secondary-foreground hover:bg-secondary",
	},
	// Off is the answer on most sources, so it is marked rather than shouted: a filled pill on the
	// majority would hide the few that are on.
	{ value: "NOT_USED", label: "Off", selected: "bg-muted text-foreground hover:bg-muted" },
] satisfies Array<{
	value: SegmentedRole;
	label: string;
	selected: string;
}>;

type SegmentedRole = Exclude<EvidenceRole, "EXHAUSTIVE">;

function selectedRole(role: EvidenceRole): SegmentedRole {
	return role === "EXHAUSTIVE" ? "REQUIRED" : role;
}

export interface PracticeEvidenceEditorProps {
	options: PracticeWorkTypeDefinitionOptions;
	/** What this practice's review reads. */
	needs: PracticeEvidenceRequirement[];
	onChange: (needs: PracticeEvidenceRequirement[]) => void;
	/** Prefix for control ids, so a form-level error can send focus to the control that failed. */
	idPrefix: string;
	disabled?: boolean;
	invalid?: boolean;
	errorId?: string;
}

/** What the review reads: collapsed, the answer is the chips; open, a source is one line. */
export function PracticeEvidenceEditor({
	options,
	needs,
	onChange,
	idPrefix,
	disabled = false,
	invalid = false,
	errorId,
}: PracticeEvidenceEditorProps) {
	// Open from the start when this occasion is already invalid: such a form never crosses the
	// transition below, and the fix is not reachable from the collapsed summary.
	const [open, setOpen] = useState(invalid);
	// On the transition into invalid, not on the flag, which stays true while the author fixes it —
	// an editor re-opening under the caret on every keystroke would be unusable.
	// https://react.dev/learn/you-might-not-need-an-effect
	const [lastInvalid, setLastInvalid] = useState(invalid);
	if (invalid !== lastInvalid) {
		setLastInvalid(invalid);
		if (invalid) setOpen(true);
	}
	const required = options.allowedSources.filter(
		(source) => roleOf(needs, source.sourceKind) !== "NOT_USED" && !isContextual(needs, source),
	);
	const contextual = options.allowedSources.filter((source) => isContextual(needs, source));
	const usesRecommendedNeeds = deepEqual(needs, options.recommendedNeeds);

	return (
		<FieldSet
			id={`${idPrefix}-evidence`}
			data-invalid={invalid || undefined}
			aria-describedby={errorId}
			aria-label="What this review reads"
			// A focus target for a form-level error, not a Tab stop.
			tabIndex={-1}
		>
			<FieldLegend variant="label">Reads</FieldLegend>
			<dl className="grid gap-x-3 gap-y-1.5 text-sm sm:grid-cols-[6.5rem_1fr]">
				<dt className="text-muted-foreground">Must have</dt>
				<dd className="flex flex-wrap gap-1.5">
					{required.length > 0 ? (
						required.map((source) => (
							<Badge key={source.sourceKind} variant="secondary">
								{source.displayName}
								{/* "captured whole", never "nothing in the world is missing": the claim is about
								    this capture of this source, which is all the review can see. */}
								{roleOf(needs, source.sourceKind) === "EXHAUSTIVE" && (
									<span className="text-muted-foreground">· captured whole</span>
								)}
							</Badge>
						))
					) : (
						<span className="text-muted-foreground">Nothing yet</span>
					)}
				</dd>
				<dt className="text-muted-foreground">May also use</dt>
				<dd className="flex flex-wrap gap-1.5">
					{contextual.length > 0 ? (
						contextual.map((source) => (
							<Badge key={source.sourceKind} variant="outline">
								{source.displayName}
							</Badge>
						))
					) : (
						<span className="text-muted-foreground">Nothing</span>
					)}
				</dd>
			</dl>
			<Collapsible open={open} onOpenChange={setOpen}>
				<div className="flex flex-wrap items-center gap-2">
					<CollapsibleTrigger
						disabled={disabled}
						render={
							<Button type="button" variant="outline" size="sm" disabled={disabled}>
								<ChevronRightIcon className="size-4 transition-transform group-aria-expanded:rotate-90" />
								Choose sources
							</Button>
						}
						className="group"
					/>
					{!usesRecommendedNeeds && (
						<Button
							type="button"
							variant="ghost"
							size="sm"
							disabled={disabled}
							onClick={() => onChange([...options.recommendedNeeds])}
						>
							<RotateCcwIcon className="size-4" />
							Use recommended evidence
						</Button>
					)}
				</div>
				<CollapsibleContent className="mt-3 space-y-4">
					<FieldDescription>
						Choosing a source does not collect or authorize it; instance governance and workspace
						integrations control that separately.
					</FieldDescription>
					{groupEvidenceSources(options.allowedSources).map((group) => (
						<div key={group.family} className="space-y-1.5">
							<p className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
								<group.def.icon className="size-3.5" aria-hidden />
								{group.def.label}
							</p>
							<ul className="divide-y overflow-hidden rounded-lg border">
								{group.sources.map((source) => (
									<SourceRow
										key={source.sourceKind}
										source={source}
										role={roleOf(needs, source.sourceKind)}
										idPrefix={idPrefix}
										disabled={disabled}
										onRoleChange={(role) => onChange(withRole(needs, source.sourceKind, role))}
									/>
								))}
							</ul>
						</div>
					))}
				</CollapsibleContent>
			</Collapsible>
		</FieldSet>
	);
}

function isContextual(
	needs: readonly PracticeEvidenceRequirement[],
	source: PracticeEvidenceSourceOption,
): boolean {
	return roleOf(needs, source.sourceKind) === "CONTEXTUAL";
}

interface SourceRowProps {
	source: PracticeEvidenceSourceOption;
	role: EvidenceRole;
	idPrefix: string;
	disabled: boolean;
	onRoleChange: (role: EvidenceRole) => void;
}

function SourceRow({ source, role, idPrefix, disabled, onRoleChange }: SourceRowProps) {
	const controlId = `${idPrefix}-source-${source.sourceKind}`;
	const inUse = role !== "NOT_USED";
	const quality = evidenceQualityRequirement(source.requiredQuality);
	return (
		<li className={cn("flex flex-wrap items-start gap-x-4 gap-y-2 p-2.5", inUse && "bg-muted/40")}>
			<div className="min-w-0 flex-1 basis-56">
				<p className="text-sm font-medium">{source.displayName}</p>
				{/* Clamped: the list is one line per source, and a source's own prose is not. */}
				<p className="line-clamp-2 text-xs text-muted-foreground">{source.description}</p>
				{inUse && quality && <p className="mt-0.5 text-xs text-muted-foreground">{quality}</p>}
				<AbsenceClaim
					source={source}
					role={role}
					controlId={controlId}
					disabled={disabled}
					onRoleChange={onRoleChange}
				/>
			</div>
			{/* Radios, not the toggle group, on semantics: the roles are mutually exclusive and exactly
			    one always holds, which is what a radio group means. A toggle group is independently
			    pressed buttons, which this would then have to stop from all being off at once. */}
			<RadioGroup
				className="flex w-fit shrink-0 gap-0 overflow-hidden rounded-lg border"
				disabled={disabled}
				aria-label={`How ${source.displayName} is used`}
				value={selectedRole(role)}
				onValueChange={(next) => onRoleChange(next)}
			>
				{EVIDENCE_ROLE_OPTIONS.map((option) => (
					<label
						key={option.value}
						htmlFor={`${controlId}-${option.value}`}
						className={cn(
							"cursor-pointer border-l px-2.5 py-1 text-[0.8rem] font-medium text-muted-foreground transition-colors first:border-l-0",
							"hover:bg-muted",
							"has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-inset has-[:focus-visible]:ring-ring",
							selectedRole(role) === option.value && option.selected,
							disabled && "cursor-not-allowed opacity-70",
						)}
					>
						{/* Clipped, not replaced: the radio keeps its role, arrow-key navigation and name. */}
						<span className="sr-only">
							<RadioGroupItem
								id={`${controlId}-${option.value}`}
								value={option.value}
								disabled={disabled}
							/>
						</span>
						{option.label}
					</label>
				))}
			</RadioGroup>
		</li>
	);
}

interface AbsenceClaimProps {
	source: PracticeEvidenceSourceOption;
	role: EvidenceRole;
	controlId: string;
	disabled: boolean;
	onRoleChange: (role: EvidenceRole) => void;
}

/**
 * The stance, named by what it lets the review do and what it costs. Not "can say what is missing":
 * completeness is measured against one capture of one source, so the claim never reaches beyond that
 * source — which is why the bound the capture is taken under is on screen whether or not it is
 * ticked, rather than left to be discovered on the review that refused.
 *
 * Absent — not present-and-unselectable — where the source contract can never promise a whole
 * capture, since choosing it there is a request the server refuses.
 */
function AbsenceClaim({ source, role, controlId, disabled, onRoleChange }: AbsenceClaimProps) {
	if (role === "CONTEXTUAL" || role === "NOT_USED") return null;
	if (!source.supportsExhaustiveEvidence) return null;
	const checkboxId = `${controlId}-exhaustive`;
	const claimed = role === "EXHAUSTIVE";
	const scopeId = `${checkboxId}-scope`;
	return (
		<Field orientation="horizontal" className="mt-1.5 gap-2" data-disabled={disabled}>
			<Checkbox
				id={checkboxId}
				disabled={disabled}
				checked={claimed}
				onCheckedChange={(checked) => onRoleChange(checked ? "EXHAUSTIVE" : "REQUIRED")}
				aria-describedby={scopeId}
			/>
			{/* Label and description are siblings, never nested: a `<label>` takes phrasing content only,
			    and a description inside it joins the checkbox's accessible name. */}
			<FieldContent>
				<FieldLabel htmlFor={checkboxId} className="text-xs font-normal">
					May claim something is absent from {source.displayName}
				</FieldLabel>
				<FieldDescription id={scopeId}>
					{claimed && (
						<>
							A partial capture then refuses the review: an incomplete list cannot tell “it is not
							there” apart from “we did not fetch it”.{" "}
						</>
					)}
					{source.selectionScope}
				</FieldDescription>
			</FieldContent>
		</Field>
	);
}
