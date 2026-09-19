import { createFileRoute, redirect } from "@tanstack/react-router";

import {
	curatedCatalogSearchSchema,
	curatedPracticeLevel,
} from "@/components/admin/curated-catalog/curated-catalog-search";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";

/** Kept so a bookmarked link lands on the catalog with the new-practice level open. */
export const Route = createFileRoute("/_authenticated/admin/catalog/practices/new")({
	validateSearch: curatedCatalogSearchSchema,
	beforeLoad: ({ search }) => {
		throw redirect({
			to: "/admin/catalog",
			search: { ...search, detail: [detailStackKey(curatedPracticeLevel())] },
		});
	},
});
