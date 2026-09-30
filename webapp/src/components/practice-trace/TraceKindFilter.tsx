import { SelectFilter } from "@/components/common/SelectFilter";
import { ARTIFACT_KIND_VALUES, artifactKindPluralLabel } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

export interface TraceKindFilterProps {
	/** The kinds of the work the list shows now, offered beside the ones this build knows. */
	seen: string[];
	/**
	 * The kind being filtered for, or `undefined` for all work. A free string rather than one this
	 * build knows: the server derives kinds from whichever integrations are registered, so an unknown
	 * one costs a 400 the list reports, where a narrowed one would quietly ignore the reader's filter.
	 */
	value: string | undefined;
	onChange: (kind: string | undefined) => void;
}

/** Which kind of work to show, on every list that narrows by one. */
export function TraceKindFilter({ seen, value, onChange }: TraceKindFilterProps) {
	// No endpoint enumerates the kinds, so the choices are the ones this build knows plus any the
	// list shows — and always the active filter, so a filter arriving by link can be seen and cleared.
	const kinds = new Set([...ARTIFACT_KIND_VALUES, ...seen, ...(hasText(value) ? [value] : [])]);
	return (
		<SelectFilter
			label="Show"
			allLabel="All work"
			options={[...kinds].map((kind) => ({ value: kind, label: artifactKindPluralLabel(kind) }))}
			value={value}
			onChange={onChange}
		/>
	);
}
