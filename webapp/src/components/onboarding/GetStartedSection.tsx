import { type GetStartedInputs, getStartedState } from "./get-started-rows";
import { GetStarted, type GetStartedProps } from "./GetStarted";

export interface GetStartedSectionProps {
	/** The reader's facts and the controls the rows lead to, as the route read them. */
	inputs: GetStartedInputs;
	fold?: GetStartedProps["fold"];
}

/**
 * Get started built from the route's facts. A module of its own, so a route can load it only while a
 * setup step is open: most readers never see the list, and they never download it.
 */
export function GetStartedSection({ inputs, fold }: GetStartedSectionProps) {
	return <GetStarted state={getStartedState(inputs)} fold={fold} />;
}
