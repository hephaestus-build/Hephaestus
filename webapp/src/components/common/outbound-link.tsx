import { ExternalLinkIcon } from "lucide-react";

import { FOCUS_RING } from "@/components/common/focus";

/**
 * The look of a link in running text that answers a press (`webapp/DESIGN.md` § Practice surfaces
 * palette). `InlineLink` and `DocLink` share it. `DocLink` stays a plain `<a>`, so a page with a docs
 * link loads only this module and not `InlineLink`'s other modes.
 */
export const INLINE_LINK_CLASS = [
	"cursor-pointer rounded-sm text-left decoration-1 underline-offset-3 hover:text-highlight hover:underline focus-visible:text-highlight focus-visible:underline",
	FOCUS_RING,
].join(" ");

/** The warning a link that opens a new tab carries: the outbound icon, and the same words for a screen reader. */
export function OutboundMark() {
	return (
		<>
			<ExternalLinkIcon className="ms-0.5 inline size-3 align-baseline" aria-hidden />
			<span className="sr-only"> (opens in a new tab)</span>
		</>
	);
}
