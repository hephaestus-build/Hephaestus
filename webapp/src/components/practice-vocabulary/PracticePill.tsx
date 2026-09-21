import { HIT_AREA_24 } from "@/components/common/focus";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

export interface PracticePillProps {
	name: string;
	/** Opens the practice's level; without it the pill is a word. */
	onOpen?: () => void;
	className?: string;
}

/**
 * A practice named as the plain grey badge, everywhere a surface names one: a feedback card's
 * head, a held row on the Heph card, a table's subject cell. A button when there is somewhere
 * to open it, so the name is the control and a keyboard reaches it.
 */
export function PracticePill({ name, onOpen, className }: PracticePillProps) {
	return (
		<Badge
			variant="secondary"
			render={onOpen ? <button type="button" onClick={onOpen} /> : undefined}
			className={cn(
				"max-w-full",
				onOpen && [HIT_AREA_24, "cursor-pointer hover:bg-secondary/80"],
				className,
			)}
		>
			<span className="truncate">{name}</span>
		</Badge>
	);
}
