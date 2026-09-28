import type { ReactElement } from "react";

import { Empty, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";

export interface ActivityEmptyProps {
	icon: ReactElement;
	/** What is missing, in five words or fewer and no closing period: "No activity in this range". */
	title: string;
}

/** An empty list on an activity surface, in the place and at the width of the list it stands for. */
export function ActivityEmpty({ icon, title }: ActivityEmptyProps) {
	return (
		<Empty variant="outlined">
			<EmptyHeader>
				<EmptyMedia variant="icon">{icon}</EmptyMedia>
				<EmptyTitle>{title}</EmptyTitle>
			</EmptyHeader>
		</Empty>
	);
}
