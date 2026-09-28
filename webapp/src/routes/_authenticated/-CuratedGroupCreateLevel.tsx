import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	adminCreateCuratedGroupMutation,
	adminGetCuratedCatalogQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { CuratedFormLevel } from "@/components/admin/curated-catalog/CuratedFormLevel";
import { CuratedGroupForm } from "@/components/admin/curated-catalog/CuratedGroupForm";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { problemDetailOf } from "@/lib/problem-detail";

export interface CuratedGroupCreateLevelProps {
	nested?: boolean;
	/** Where the level sits, from the host's `levelPathAt`. */
	path: LevelPath;
	onDone: () => void;
}

export function CuratedGroupCreateLevel({ nested, path, onDone }: CuratedGroupCreateLevelProps) {
	const queryClient = useQueryClient();
	const createGroup = useMutation({
		...adminCreateCuratedGroupMutation(),
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
			toast.success("Group created");
			onDone();
		},
		onError: (error) =>
			toast.error("Couldn't create the group", { description: problemDetailOf(error) }),
	});

	return (
		<CuratedFormLevel kind="group-new" nested={nested} path={path}>
			<CuratedGroupForm
				mode="create"
				cancel={<LevelCancel />}
				isPending={createGroup.isPending}
				onSubmit={({ slug, ...definition }) => createGroup.mutate({ body: { slug, definition } })}
			/>
		</CuratedFormLevel>
	);
}
