import {
	Field,
	FieldContent,
	FieldDescription,
	FieldLabel,
	FieldTitle,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";

export type ModelAccessScope = "ALL" | "SELECTED";

export interface ModelAccessScopeChoiceProps {
	idPrefix: string;
	/** A `role="radiogroup"` is not named by an enclosing legend, so it needs its own name. */
	label: string;
	value: ModelAccessScope;
	onChange: (value: ModelAccessScope) => void;
}

export function ModelAccessScopeChoice({
	idPrefix,
	label,
	value,
	onChange,
}: ModelAccessScopeChoiceProps) {
	return (
		<RadioGroup
			value={value}
			onValueChange={(next) => onChange(next)}
			className="gap-3"
			aria-label={label}
		>
			<FieldLabel htmlFor={`${idPrefix}-all`}>
				<Field orientation="horizontal">
					<FieldContent>
						<FieldTitle>All workspaces</FieldTitle>
						<FieldDescription>
							Every current and future workspace can select the model.
						</FieldDescription>
					</FieldContent>
					<RadioGroupItem id={`${idPrefix}-all`} value="ALL" />
				</Field>
			</FieldLabel>
			<FieldLabel htmlFor={`${idPrefix}-selected`}>
				<Field orientation="horizontal">
					<FieldContent>
						<FieldTitle>Selected workspaces</FieldTitle>
						<FieldDescription>
							Only explicitly selected workspaces can select the model.
						</FieldDescription>
					</FieldContent>
					<RadioGroupItem id={`${idPrefix}-selected`} value="SELECTED" />
				</Field>
			</FieldLabel>
		</RadioGroup>
	);
}
