import { CircleSlashIcon } from "lucide-react";

import type { ObservationInvalidation } from "@/api/types.gen";

import type { StatusDef } from "@/components/common/status-def";

export type ProviderCopy = ObservationInvalidation["providerCopy"];

/** The mark on an observation a workspace admin found wrong when it was made. */
export const MARKED_INCORRECT_DEF: StatusDef = {
	label: "Marked incorrect",
	icon: CircleSlashIcon,
	badgeVariant: "destructive",
	description:
		"A workspace admin marked this observation as incorrect. It counts toward nothing current.",
};

/** What became of the comments already on the work while a correction is in force. */
export const PROVIDER_COPY_IN_FORCE: Record<ProviderCopy, string> = {
	PENDING:
		"Hephaestus is still bringing the comments it posted about this observation in line with the correction, or still checking whether a comment it tried to post arrived.",
	NONE: "No comment about it was found on the work.",
	UPDATED:
		"Every summary comment Hephaestus posted about it now opens with a correction notice, or has since been deleted.",
	INLINE_REMAINS:
		"Inline comments Hephaestus posted about it are still on the work unchanged, because they cannot be edited from here; any summary comment now carries a correction notice. Remove or answer the inline comments on the pull request or merge request.",
	UNRESOLVED:
		"Hephaestus cannot correct every comment about it: a summary could not be edited, the provider accepted a comment without saying which one it is, or a comment it tried to post has not been confirmed. Check the pull request or merge request and correct it there; Hephaestus keeps checking and updates this if it finds the comment.",
};

/** The same after a restore, where only a notice Hephaestus added has anything to undo. */
export const PROVIDER_COPY_RESTORED: Record<ProviderCopy, string | undefined> = {
	PENDING: "Removing the correction notice from posted comments…",
	NONE: undefined,
	UPDATED: "The correction notice was removed from posted comments.",
	INLINE_REMAINS: undefined,
	UNRESOLVED:
		"A summary comment could not be edited, so it may not match the restored observation.",
};
