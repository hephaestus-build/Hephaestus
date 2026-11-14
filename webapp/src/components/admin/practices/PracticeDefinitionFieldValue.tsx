import type { PracticeDefinition } from "@/api/types.gen";
import {
	PracticeGuideVersion,
	PracticeVisualVersion,
} from "@/components/admin/practice-editor/practice-guidance-version";
import {
	definitionFieldText,
	type PracticeDefinitionField,
} from "@/components/admin/practices/practice-definition-fields";
import {
	CodeBlock,
	CodeBlockActions,
	CodeBlockContent,
	CodeBlockCopyButton,
} from "@/components/ui/code-block";

export interface PracticeDefinitionFieldValueProps {
	definition: PracticeDefinition;
	field: PracticeDefinitionField;
}

export function PracticeDefinitionFieldValue({
	definition,
	field,
}: PracticeDefinitionFieldValueProps) {
	const { visual, guide } = definition.guidance;
	// Only a practice script is code. Every other field is prose, so it keeps the reading face.
	const script = field === "SCRIPT" ? definition.script : null;
	return (
		<div className="flex flex-col gap-2">
			{script === null ? (
				<pre className="max-h-48 overflow-auto font-sans text-xs break-words whitespace-pre-wrap">
					{definitionFieldText(definition, field)}
				</pre>
			) : (
				<CodeBlock size="sm">
					<CodeBlockActions>
						<CodeBlockCopyButton value={script} aria-label="Copy practice script" />
					</CodeBlockActions>
					<CodeBlockContent lineNumbers className="max-h-48">
						{script}
					</CodeBlockContent>
				</CodeBlock>
			)}
			{field === "GUIDANCE" && visual !== null && <PracticeVisualVersion visual={visual} />}
			{field === "GUIDANCE" && guide !== null && <PracticeGuideVersion guide={guide} />}
		</div>
	);
}
