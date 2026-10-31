import { Button } from "@/components/ui/button";

export interface InheritedSettingHintProps {
	label: string;
	overridden: boolean;
	inheritedValue: string;
	disabled: boolean;
	onReset: () => void;
}

/** The default a workspace setting follows, or a way back to it once the workspace set its own. */
export function InheritedSettingHint({
	label,
	overridden,
	inheritedValue,
	disabled,
	onReset,
}: InheritedSettingHintProps) {
	if (!overridden) {
		return (
			<span className="self-start text-xs text-muted-foreground">Default: {inheritedValue}</span>
		);
	}

	return (
		<div className="text-left">
			<Button
				variant="link"
				size="inline"
				className="text-xs"
				aria-label={`Use default for ${label}`}
				disabled={disabled}
				onClick={onReset}
			>
				Use default
			</Button>
		</div>
	);
}
