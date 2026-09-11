import { cn } from "cn";
import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ButtonGroup, ButtonGroupText } from "@/components/ui/button-group";

export interface RemovableTokenProps {
	label: string;
	removeLabel: string;
	onRemove: () => void;
	disabled?: boolean;
	className?: string;
}

export function RemovableToken({
	label,
	removeLabel,
	onRemove,
	disabled = false,
	className,
}: RemovableTokenProps) {
	return (
		<ButtonGroup>
			<ButtonGroupText className={cn("h-8 min-w-0 max-w-[60vw] sm:max-w-xs", className)}>
				<span className="truncate" title={label}>
					{label}
				</span>
			</ButtonGroupText>
			<Button
				variant="outline"
				size="sm"
				className="h-8"
				aria-label={removeLabel}
				disabled={disabled}
				onClick={onRemove}
			>
				<XIcon aria-hidden />
			</Button>
		</ButtonGroup>
	);
}
