import { createFileRoute, redirect } from "@tanstack/react-router";

import {
	practiceFormLevel,
	practiceSetupSearchSchema,
} from "@/components/admin/practices/practice-search";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";

/** Kept so a bookmarked link lands on practice setup with the new-practice level open. */
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/new")({
	validateSearch: practiceSetupSearchSchema,
	beforeLoad: ({ params, search }) => {
		throw redirect({
			to: "/w/$workspaceSlug/admin/practices",
			params,
			search: { ...search, detail: [detailStackKey(practiceFormLevel())] },
		});
	},
});
