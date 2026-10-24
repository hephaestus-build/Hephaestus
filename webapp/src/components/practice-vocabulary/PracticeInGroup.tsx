import type { ComponentType } from "react";

import { cn } from "cn";

import { GroupName } from "./GroupName";
import { PracticePill, type PracticeSquare } from "./PracticePill";

export interface PracticeInGroupProps {
	practiceName: string;
	/** The group's icon and color for the practice's square; without it the square is neutral. */
	practiceSquare?: PracticeSquare;
	/** Opens the practice; without it the pill is plain. */
	onOpenPractice?: () => void;
	groupName: string;
	groupIcon: ComponentType<{ className?: string }>;
	/** The group's pill classes from `getGroupVisual`; without them the group's name is muted. */
	groupPill?: string;
	onOpenGroup?: () => void;
	className?: string;
}

/**
 * "<practice> in <group>": the practice's pill, then its group with its icon, on one line, as a
 * feedback card's head names what it is about. Wherever a row or a card names a practice with its
 * group, it reads the same.
 */
export function PracticeInGroup({
	practiceName,
	practiceSquare,
	onOpenPractice,
	groupName,
	groupIcon,
	groupPill,
	onOpenGroup,
	className,
}: PracticeInGroupProps) {
	return (
		<div className={cn("flex flex-wrap items-center gap-3 text-sm", className)}>
			<PracticePill name={practiceName} group={practiceSquare} onOpen={onOpenPractice} />
			{/* "in" moves with the group, so a wrap never leaves it at the end of the line. */}
			<span className="inline-flex max-w-full min-w-0 items-center gap-3">
				<span className="text-muted-foreground">in</span>
				<GroupName name={groupName} icon={groupIcon} pill={groupPill} onOpen={onOpenGroup} />
			</span>
		</div>
	);
}
