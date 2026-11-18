import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "@/lib/toast";

import {
	getPracticeDefinitionOptionsOptions,
	getPracticeQueryKey,
	listPracticesQueryKey,
	updatePracticeMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Practice } from "@/api/types.gen";
import { type BulkReviewer, withReviewer } from "@/components/admin/practice-editor/reviewed-by";
import { practiceCatalogStructureScope, upsertPractice } from "@/hooks/practice-catalog-cache";
import { refreshPracticeGuidance } from "@/hooks/practice-guidance-cache";
import type { BulkProgress } from "@/hooks/use-practice-feedback-mode-mutations";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

/** Whether the practice already has this reviewer, so the change leaves it as it is. */
function hasReviewer(practice: Practice, reviewer: BulkReviewer): boolean {
	const current = practice.currentVersion.definition?.reviewedBy;
	return (
		current !== undefined &&
		current.by === reviewer.by &&
		(reviewer.by !== "PEOPLE" || current.humanReason?.description === reviewer.reason.trim())
	);
}

function practices(count: number): string {
	return `${count} ${count === 1 ? "practice" : "practices"}`;
}

/**
 * Changes who reviews many practices, one save at a time, as the practice editor saves one: a new version under the
 * current source contract, in the catalog's structure scope. A practice that a newer version changed meanwhile, or
 * that its new reviewer cannot review, is left as it is, and the summary names it with the server's reason.
 */
export function useBulkReviewerChange(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const [bulk, setBulk] = useState<BulkProgress | null>(null);
	const listQueryKey = listPracticesQueryKey({ path: { workspaceSlug } });
	const update = useMutation({
		...updatePracticeMutation(),
		scope: practiceCatalogStructureScope(workspaceSlug),
	});

	const setManyReviewers = async (selected: readonly Practice[], reviewer: BulkReviewer) => {
		const changing = selected.filter((practice) => !hasReviewer(practice, reviewer));
		const unchanged = selected.length - changing.length;
		if (changing.length === 0) {
			toast.success("Every selected practice already has this reviewer");
			return;
		}
		setBulk({ done: 0, total: changing.length });
		const { sourceContract } = await queryClient.query(
			getPracticeDefinitionOptionsOptions({ path: { workspaceSlug } }),
		);
		const failed: { name: string; reason: string; stale: boolean }[] = [];
		for (const [index, practice] of changing.entries()) {
			const { definition } = practice.currentVersion;
			const name = definition?.name ?? practice.slug;
			if (definition === null) {
				failed.push({ name, reason: "Its definition cannot be read.", stale: false });
			} else {
				try {
					const updated = await update.mutateAsync({
						path: { workspaceSlug, practiceSlug: practice.slug },
						body: {
							expectedVersionId: practice.currentVersion.id,
							definition: withReviewer(definition, reviewer, sourceContract),
						},
					});
					queryClient.setQueryData(
						getPracticeQueryKey({ path: { workspaceSlug, practiceSlug: practice.slug } }),
						updated,
					);
					queryClient.setQueryData<Practice[]>(listQueryKey, (rows) =>
						rows ? upsertPractice(rows, updated) : rows,
					);
					await refreshPracticeGuidance(queryClient, workspaceSlug, practice.slug);
				} catch (error) {
					failed.push({
						name,
						reason: problemDetailOf(error),
						stale: problemStatusOf(error) === 409,
					});
				}
			}
			setBulk({ done: index + 1, total: changing.length });
		}
		// The list holds the version ids that the next change sends, so it is fresh before the menu comes back.
		await queryClient.invalidateQueries({ queryKey: listQueryKey });
		setBulk(null);

		const changed = changing.length - failed.length;
		const already = unchanged > 0 ? ` ${practices(unchanged)} already had this reviewer.` : "";
		if (failed.length === 0) {
			toast.success(`Changed ${practices(changed)}.${already}`);
			return;
		}
		const first = failed[0];
		const description = `${failed
			.slice(0, 3)
			.map((failure) => failure.name)
			.join(", ")}${failed.length > 3 ? ", …" : ""}: ${first?.reason ?? ""}${
			failed.every((failure) => failure.stale) ? " Reload the page and try again." : ""
		}`;
		if (changed === 0) {
			toast.error(`We could not change ${practices(failed.length)}.${already}`, { description });
		} else {
			toast.warning(
				`Changed ${changed} of ${changing.length}. ${practices(failed.length)} kept their reviewer.${already}`,
				{
					description,
				},
			);
		}
	};

	return { bulk, setManyReviewers };
}
