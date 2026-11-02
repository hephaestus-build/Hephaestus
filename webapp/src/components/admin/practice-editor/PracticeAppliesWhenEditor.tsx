import type { AppliesWhenClause, PracticeItemTypeOption } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import {
	Field,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import {
	Select,
	SelectContent,
	SelectGroup,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { hasText } from "@/lib/text";

import { type ApplicabilityClause, type AppliesWhenDraft, clauseKind } from "./applies-when";

const CONDITIONS = {
	CHANGED_PATH: {
		label: "A file changed",
		values: "File paths",
		description: "One path pattern per line, such as **/*.swift.",
	},
	DIFF_CONTAINS: {
		label: "The diff contains text",
		values: "Text to find",
		description: "One phrase per line. Any phrase can match.",
	},
	HAS_ITEMS: {
		label: "A source is not empty",
		values: "Source",
		description: "Choose the source to check.",
	},
	MERGED: {
		label: "The work has merged",
		values: "",
		description: "",
	},
} satisfies Record<ApplicabilityClause, { label: string; values: string; description: string }>;

function clauseOf(kind: ApplicabilityClause, text = ""): AppliesWhenClause {
	switch (kind) {
		case "CHANGED_PATH": {
			return { changedPath: text.split("\n") };
		}
		case "DIFF_CONTAINS": {
			return { diffContains: text.split("\n") };
		}
		case "HAS_ITEMS": {
			return { hasItems: text };
		}
		case "MERGED": {
			return { merged: true };
		}
	}
}

export interface PracticeAppliesWhenEditorProps {
	value: AppliesWhenDraft;
	onChange: (value: AppliesWhenDraft) => void;
	itemType: PracticeItemTypeOption | undefined;
	id: string;
	disabled?: boolean;
	error?: string;
}

export function PracticeAppliesWhenEditor({
	value,
	onChange,
	itemType,
	id,
	disabled = false,
	error,
}: PracticeAppliesWhenEditorProps) {
	const sources = itemType?.applicabilitySources ?? [];
	const available = (itemType?.applicabilityClauses ?? []).filter(
		(kind) => kind !== "HAS_ITEMS" || sources.length > 0,
	);
	const replace = (index: number, clause: AppliesWhenClause) =>
		onChange({
			...value,
			clauses: value.clauses.map((current, position) => (position === index ? clause : current)),
		});
	return (
		<FieldSet
			id={id}
			tabIndex={-1}
			disabled={disabled}
			aria-describedby={`${id}-description${hasText(error) ? ` ${id}-error` : ""}`}
			data-invalid={hasText(error) || undefined}
		>
			<FieldLegend variant="label">Applies when</FieldLegend>
			<FieldDescription id={`${id}-description`}>
				{value.clauses.length === 0
					? "Reviews can start for all work of this kind. Add a condition to limit which work applies."
					: "A review can start when any condition holds."}
			</FieldDescription>
			{value.clauses.map((clause, index) => {
				const kind = clauseKind(clause);
				const presentation = CONDITIONS[kind];
				const controlId = `${id}-${index}`;
				const text =
					(clause.changedPath ?? clause.diffContains)?.join("\n") ?? clause.hasItems ?? "";
				return (
					<FieldGroup key={controlId}>
						<Field orientation="responsive">
							<FieldLabel htmlFor={`${controlId}-kind`}>Condition {index + 1}</FieldLabel>
							<Select
								value={kind}
								disabled={disabled}
								onValueChange={(next) => {
									if (next !== null) {
										replace(index, clauseOf(next));
									}
								}}
								items={available.map((option) => ({
									value: option,
									label: CONDITIONS[option].label,
								}))}
							>
								<SelectTrigger id={`${controlId}-kind`} className="w-full @md/field-group:w-56">
									<SelectValue>{presentation.label}</SelectValue>
								</SelectTrigger>
								<SelectContent aria-label={`Condition ${index + 1}`}>
									<SelectGroup>
										{available.map((option) => (
											<SelectItem key={option} value={option}>
												{CONDITIONS[option].label}
											</SelectItem>
										))}
									</SelectGroup>
								</SelectContent>
							</Select>
						</Field>
						{kind === "MERGED" ? null : (
							<Field data-invalid={hasText(error) || undefined}>
								<FieldLabel htmlFor={`${controlId}-values`}>
									{presentation.values} {index + 1}
								</FieldLabel>
								<FieldDescription id={`${controlId}-help`}>
									{presentation.description}
								</FieldDescription>
								{kind === "HAS_ITEMS" ? (
									<Select
										value={hasText(text) ? text : null}
										disabled={disabled}
										items={sources.map((source) => ({ value: source.source, label: source.label }))}
										onValueChange={(next) => {
											if (next !== null) {
												replace(index, { hasItems: next });
											}
										}}
									>
										<SelectTrigger
											id={`${controlId}-values`}
											className="w-full"
											aria-describedby={`${controlId}-help`}
											aria-invalid={hasText(error)}
										>
											<SelectValue placeholder="Choose a source" />
										</SelectTrigger>
										<SelectContent aria-label={`Source ${index + 1}`}>
											<SelectGroup>
												{sources.map((source) => (
													<SelectItem key={source.source} value={source.source}>
														{source.label}
													</SelectItem>
												))}
											</SelectGroup>
										</SelectContent>
									</Select>
								) : (
									<Textarea
										id={`${controlId}-values`}
										value={text}
										disabled={disabled}
										aria-describedby={`${controlId}-help`}
										aria-invalid={hasText(error)}
										rows={3}
										spellCheck={false}
										onChange={(event) => replace(index, clauseOf(kind, event.target.value))}
									/>
								)}
							</Field>
						)}
						<Button
							type="button"
							variant="outline"
							size="sm"
							disabled={disabled}
							onClick={() =>
								onChange({
									...value,
									clauses: value.clauses.filter((_, position) => position !== index),
								})
							}
						>
							Remove condition {index + 1}
						</Button>
					</FieldGroup>
				);
			})}
			{hasText(error) && <FieldError id={`${id}-error`}>{error}</FieldError>}
			<Button
				type="button"
				variant="outline"
				size="sm"
				disabled={disabled || available.length === 0}
				onClick={() => {
					const kind = available[0];
					if (kind !== undefined) {
						onChange({ ...value, clauses: [...value.clauses, clauseOf(kind)] });
					}
				}}
			>
				Add condition
			</Button>
		</FieldSet>
	);
}
