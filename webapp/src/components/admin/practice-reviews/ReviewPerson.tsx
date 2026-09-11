import { cn } from "cn";
import type { ReviewSubject } from "@/api/types.gen";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { getInitials } from "@/lib/avatar";

import { subjectLabel } from "./review-format";

export interface ReviewPersonProps {
	person: ReviewSubject | undefined;
	/**
	 * Says which person this is when a surface shows two — "To" and "About" on a piece of feedback
	 * whose recipient is not its subject. Omitted everywhere only one person is in play, where a
	 * prefix would be reading out the column header.
	 */
	prefix?: string;
	className?: string;
}

/** One person, as a chip: avatar and name, sized to sit in a row of badges. */
export function ReviewPerson({ person, prefix, className }: ReviewPersonProps) {
	return (
		<span
			className={cn(
				"inline-flex min-w-0 max-w-full items-center gap-1.5 rounded-md border px-1.5 py-0.5 text-xs",
				className,
			)}
		>
			<Avatar className="size-4 shrink-0">
				<AvatarImage src={person?.avatarUrl} alt="" />
				<AvatarFallback className="text-[0.625rem]">
					{getInitials(person?.name, person?.login)}
				</AvatarFallback>
			</Avatar>
			<span className="min-w-0 break-words">
				{prefix && <span className="text-muted-foreground">{prefix} </span>}
				{subjectLabel(person)}
			</span>
		</span>
	);
}
