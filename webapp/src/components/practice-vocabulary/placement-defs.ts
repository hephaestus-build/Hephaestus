import { BotMessageSquareIcon, Link2Icon, MapPinIcon, MessageSquareTextIcon } from "lucide-react";

import type { ReviewedWorkRef, ReviewPlacement } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";
import { DELIVERY_PLACE_DEFS, type DeliveryPlace } from "./delivery-place-defs";

export type PlacementType = ReviewPlacement["placementType"];

/**
 * The exact spot a delivered piece of feedback landed — a finer grain of the same "where" axis as
 * `DELIVERY_PLACE_DEFS`, and only ever known once something was posted. A note proposed for some
 * lines is still proposed `INLINE`; `LOCATION_COMMENT` only ever describes where its copy appeared.
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
		description: "Spoken by Heph during a chat with the developer.",
	},
	LOCATION_COMMENT: {
		label: "As a comment linking to the lines",
		icon: Link2Icon,
		badgeVariant: "outline",
		description:
			"A comment on the work as a whole that links to the lines as they were reviewed. It is not attached to the lines themselves.",
	},
};

/**
 * Where a proposed placement will appear once delivered. Hephaestus posts no comment on GitLab lines: a
 * note proposed for some lines goes up as a comment on the merge request that links to them as they
 * were reviewed. The proposal itself stays a note on those lines.
 */
export function deliveredPlacementType(
	proposed: PlacementType,
	provider: ReviewedWorkRef["provider"],
): PlacementType {
	return proposed === "INLINE" && provider === "GITLAB" ? "LOCATION_COMMENT" : proposed;
}

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
