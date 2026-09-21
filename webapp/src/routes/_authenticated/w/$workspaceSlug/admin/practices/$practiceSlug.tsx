import { createFileRoute, redirect } from "@tanstack/react-router";

import {
	practiceFormLevel,
	practiceSetupSearchSchema,
} from "@/components/admin/practices/practice-search";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";

/** Kept so a bookmarked link lands on practice setup with that practice's editor level open. */
export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/$practiceSlug",
)({
	validateSearch: practiceSetupSearchSchema,
	beforeLoad: ({ params, search }) => {
		const { practiceSlug, ...rest } = params;
		throw redirect({
			to: "/w/$workspaceSlug/admin/practices",
			params: rest,
			search: { ...search, detail: [detailStackKey(practiceFormLevel(practiceSlug))] },
		});
	},
});
