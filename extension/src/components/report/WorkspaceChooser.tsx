import type { WorkspaceChoice } from "~/shared/review-context";

export interface WorkspaceChooserProps {
	/** Every workspace that knows this work. */
	choices: readonly WorkspaceChoice[];
	/** The one on screen, if any. */
	value: string | undefined;
	onChange: (slug: string) => void;
}

/**
 * More than one workspace knows this work, and they can say different things about it, so the reader
 * picks — the extension never quietly takes the first. One row of choices, wrapping when narrow.
 */
export function WorkspaceChooser({ choices, value, onChange }: WorkspaceChooserProps) {
	return (
		<fieldset className="min-w-0">
			<legend className="mb-1 text-xs font-medium text-muted-foreground">Workspace</legend>
			<div className="flex flex-wrap gap-x-4 gap-y-1.5">
				{choices.map((choice) => (
					<label
						key={choice.slug}
						className="flex min-w-0 items-center gap-2 text-sm break-words text-foreground"
					>
						<input
							type="radio"
							name="hephaestus-workspace"
							value={choice.slug}
							checked={choice.slug === value}
							onChange={() => onChange(choice.slug)}
							className="size-4 shrink-0 accent-(--link)"
						/>
						{choice.displayName}
					</label>
				))}
			</div>
		</fieldset>
	);
}
