import { BotMessageSquareIcon, MapPinIcon, MessageSquareTextIcon } from "lucide-react";

import type { ReviewPlacement } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";
import { DELIVERY_PLACE_DEFS, type DeliveryPlace } from "./delivery-place-defs";

export type PlacementType = ReviewPlacement["placementType"];

/**
 * The exact spot a delivered piece of feedback landed — a finer grain of the same "where" axis as
 * `DELIVERY_PLACE_DEFS`, and only ever known once something was posted.
 */
export const PLACEMENT_DEFS: StatusDefs<PlacementType> = {
	SUMMARY: {
		label: "As a summary comment",
		icon: MessageSquareTextIcon,
		badgeVariant: "outline",
		description: "One comment on the work as a whole.",
	},
	INLINE: {
		label: "As an inline note",
		icon: MapPinIcon,
		badgeVariant: "outline",
		description: "Anchored to specific lines, so it is read next to what it is about.",
	},
	CONVERSATION_TURN: {
		label: "As a turn in the conversation",
		icon: BotMessageSquareIcon,
		badgeVariant: "outline",
		description: "Spoken by the mentor during a chat with the developer.",
	},
};

/**
 * The most precise "where" the record supports, in one phrase: the spot with a placement, and the
 * lane without one, which is all a withheld or still-queued piece of feedback has.
 */
export function placementLabel(place: DeliveryPlace, placementType?: PlacementType): string {
	const placeLabel = DELIVERY_PLACE_DEFS[place].label;
	if (!placementType) {
		return placeLabel;
	}
	if (placementType === "CONVERSATION_TURN") {
		return PLACEMENT_DEFS.CONVERSATION_TURN.label;
	}
	return `${PLACEMENT_DEFS[placementType].label} ${placeLabel.toLowerCase()}`;
}
