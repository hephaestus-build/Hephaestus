import { WorkspacePracticeRepositoryCard } from "@/components/admin/practices/WorkspacePracticeRepositoryCard";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { usePracticeRepositorySetting } from "@/hooks/use-practice-repository-setting";

/** The practice repository card on Practice setup, with the Confirm access that its writes may ask for. */
export function PracticeRepositorySetting({ workspaceSlug }: { workspaceSlug: string }) {
	const { card, confirmation } = usePracticeRepositorySetting(workspaceSlug);
	return (
		<>
			<WorkspacePracticeRepositoryCard workspaceSlug={workspaceSlug} {...card} />
			<ConfirmAccessDialog {...confirmation} />
		</>
	);
}
