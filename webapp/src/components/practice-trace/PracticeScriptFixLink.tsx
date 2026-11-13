import { Link } from "@tanstack/react-router";
import { ArrowRightIcon } from "lucide-react";

import { practiceFormLevel } from "@/components/admin/practices/practice-search";
import { DocLink } from "@/components/common/DocLink";
import { InlineLink } from "@/components/common/InlineLink";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import {
	PRACTICE_SCRIPT_GUIDE_DEFS,
	type PracticeScriptFix,
} from "@/components/practice-vocabulary/practice-script-run-status-defs";

export interface PracticeScriptFixLinkProps {
	workspaceSlug: string;
	practiceSlug: string;
	/**
	 * The practice's name, for a screen reader, where one list repeats the same link for several
	 * practices. Absent where the link sits in the practice's own sentence.
	 */
	practiceName?: string;
	fix: PracticeScriptFix;
}

export function PracticeScriptFixLink({
	workspaceSlug,
	practiceSlug,
	practiceName,
	fix,
}: PracticeScriptFixLinkProps) {
	// Place context after the visible label so speech control can match it (WCAG 2.5.3).
	const context =
		practiceName === undefined ? null : (
			<>
				{" "}
				<span className="sr-only">for {practiceName}</span>
			</>
		);
	if (fix.kind === "GUIDE") {
		return (
			<DocLink doc={PRACTICE_SCRIPT_GUIDE_DEFS[fix.guide].doc} className="font-medium">
				{PRACTICE_SCRIPT_GUIDE_DEFS[fix.guide].label}
				{context}
			</DocLink>
		);
	}
	return (
		<InlineLink
			render={
				<Link
					to="/w/$workspaceSlug/admin/practices"
					params={{ workspaceSlug }}
					search={detailSearch(practiceFormLevel(practiceSlug))}
				/>
			}
			className="inline-flex items-center gap-1 font-medium"
		>
			Edit the script{context}
			<ArrowRightIcon className="size-3 shrink-0" aria-hidden />
		</InlineLink>
	);
}
