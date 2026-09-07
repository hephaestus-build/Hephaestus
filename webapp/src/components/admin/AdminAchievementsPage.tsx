import { RefreshCw, Trophy } from "lucide-react";

import type { ExtendedUserTeams } from "@/components/admin/types";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

import { AdminAchievementsTable } from "./AdminAchievementsTable";

interface AdminAchievementsPageProps {
	users: ExtendedUserTeams[];
	isLoading: boolean;
	workspaceSlug: string;
	error?: unknown;
	onRetry?: () => void;
	isReloading: boolean;
	isRecalculatingAll: boolean;
	recalculatingUsers: Set<string>;
	onReload: () => void;
	onRecalculateAll: () => void;
	onRecalculate: (username: string) => void;
}

export function AdminAchievementsPage({
	users,
	isLoading,
	workspaceSlug,
	error,
	onRetry,
	isReloading,
	isRecalculatingAll,
	recalculatingUsers,
	onReload,
	onRecalculateAll,
	onRecalculate,
}: AdminAchievementsPageProps) {
	return (
		<PageLayout>
			<PageHeader
				icon={<Trophy />}
				title="Achievements"
				description="Recalculate achievements for workspace members."
				actions={
					<>
						<Button
							variant="outline"
							onClick={onReload}
							disabled={isLoading || isReloading}
							className="w-full sm:w-auto"
						>
							{isReloading ? (
								<>
									<Spinner className="mr-2 h-4 w-4" />
									Reloading...
								</>
							) : (
								<>
									<RefreshCw className="mr-2 h-4 w-4" />
									Reload Definitions
								</>
							)}
						</Button>
						<Button
							onClick={onRecalculateAll}
							disabled={isLoading || isRecalculatingAll || users.length === 0}
							className="w-full sm:w-auto"
						>
							{isRecalculatingAll ? (
								<>
									<Spinner className="mr-2 h-4 w-4" />
									Recalculating All...
								</>
							) : (
								<>
									<Trophy className="mr-2 h-4 w-4" />
									Recalculate All
								</>
							)}
						</Button>
					</>
				}
			/>

			{error ? (
				<QueryErrorAlert error={error} title="Couldn't load achievements" onRetry={onRetry} />
			) : (
				<AdminAchievementsTable
					users={users}
					isLoading={isLoading}
					workspaceSlug={workspaceSlug}
					onRecalculate={onRecalculate}
					recalculatingUsers={recalculatingUsers}
				/>
			)}
		</PageLayout>
	);
}
