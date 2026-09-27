import type { ReactElement } from "react";

import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";

export interface ActivityEmptyProps {
	icon: ReactElement;
	/** What is missing, with no closing period: "Nothing in the last 7 days". */
	title: string;
	/** What would show up here. */
	description: string;
}

/** An empty list on an activity surface, in the place and at the width of the list it stands for. */
export function ActivityEmpty({ icon, title, description }: ActivityEmptyProps) {
	return (
		<Empty variant="outlined">
			<EmptyHeader>
				<EmptyMedia variant="icon">{icon}</EmptyMedia>
				<EmptyTitle>{title}</EmptyTitle>
				<EmptyDescription>{description}</EmptyDescription>
			</EmptyHeader>
		</Empty>
	);
}
