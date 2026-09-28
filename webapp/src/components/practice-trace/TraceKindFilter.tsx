import { ALL_OPTION } from "@/components/common/FilterToolbar";
import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { artifactKindPluralLabel } from "@/lib/artifact-kinds";

export interface TraceKindFilterProps {
	/** Every kind on offer, the active one included, in the order they are shown. */
	kinds: string[];
	/** The kind being filtered for, or `undefined` for all work. */
	value: string | undefined;
	onChange: (kind: string | undefined) => void;
}

/** Which kind of work to show, on every list that narrows by one. */
export function TraceKindFilter({ kinds, value, onChange }: TraceKindFilterProps) {
	const items = [
		{ value: ALL_OPTION, label: "All work" },
		...kinds.map((kind) => ({ value: kind, label: artifactKindPluralLabel(kind) })),
	];

	return (
		<div className="flex min-w-0 items-center gap-2">
			<Label
				id="trace-artifact-kind-label"
				htmlFor="trace-artifact-kind"
				className="shrink-0 text-muted-foreground"
			>
				Show
			</Label>
			<Select
				items={items}
				value={value ?? ALL_OPTION}
				onValueChange={(next) => onChange(next === ALL_OPTION ? undefined : String(next))}
			>
				<SelectTrigger id="trace-artifact-kind" className="w-56 max-w-full">
					<SelectValue placeholder="All work" />
				</SelectTrigger>
				<SelectContent aria-labelledby="trace-artifact-kind-label">
					{items.map((kind) => (
						<SelectItem key={kind.value} value={kind.value}>
							{kind.label}
						</SelectItem>
					))}
				</SelectContent>
			</Select>
		</div>
	);
}
