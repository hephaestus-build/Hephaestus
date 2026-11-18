import { CircleAlert } from "lucide-react";
import type { ComponentProps } from "react";

import type { PracticeRepository } from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import type { PracticeFolderProblem } from "@/lib/practice-repository";
import { hasText } from "@/lib/text";

/** A Move to repository that the reader asked for and has not cancelled. */
export type MoveToRepositoryRequest =
	| { phase: "confirming" }
	| { phase: "pending" }
	| { phase: "refused"; reason: string; problems: PracticeFolderProblem[] };

/** Move to repository for one practice, as the route runs it. */
export interface MoveToRepositoryControl {
	/**
	 * The workspace's practice repository: `null` when it has none, absent while that is unknown, as
	 * while it loads.
	 */
	repository?: PracticeRepository | null;
	/** The open request for this practice. Absent while no dialog is open for it. */
	request?: MoveToRepositoryRequest;
	onOpen: () => void;
	/** Asks to close the dialog. Ignored while the request runs. */
	onCancel: () => void;
	onConfirm: () => void;
}

export interface WorkspaceMoveToRepositoryDialogProps {
	practiceName: string;
	practiceSlug: string;
	control: MoveToRepositoryControl;
	/** Where focus goes once the dialog closes, such as the notice that the move shows. */
	finalFocus: ComponentProps<typeof AlertDialogContent>["finalFocus"];
}

function problemPlace({ file, line, column }: PracticeFolderProblem) {
	if (line === undefined) {
		return file;
	}
	return column === undefined
		? `${file}, line ${line}`
		: `${file}, line ${line}, column ${column}`;
}

/**
 * Asks before Hephaestus reads a practice from its folder in the practice repository. The practice
 * is read-only here afterwards, so the dialog says so and names the folder it reads. It stays open
 * while the request runs, and keeps a refusal in view with each problem of the folder: the reader
 * fixes them in the repository, then tries again.
 */
export function WorkspaceMoveToRepositoryDialog({
	practiceName,
	practiceSlug,
	control,
	finalFocus,
}: WorkspaceMoveToRepositoryDialogProps) {
	const { repository, request, onCancel, onConfirm } = control;
	const pending = request?.phase === "pending";
	const name = repository?.nameWithOwner ?? "the practice repository";
	const branch = repository?.defaultBranch;
	return (
		<AlertDialog
			open={request !== undefined}
			onOpenChange={(next) => {
				if (!next) {
					onCancel();
				}
			}}
		>
			<AlertDialogContent finalFocus={finalFocus}>
				<AlertDialogHeader>
					<AlertDialogTitle>Move “{practiceName}” to the practice repository?</AlertDialogTitle>
					<AlertDialogDescription>
						Hephaestus reads its folder{" "}
						{repository === undefined || repository === null ? (
							"in the practice repository"
						) : (
							<>
								<code>
									{repository.directory}/{practiceSlug}/
								</code>{" "}
								in {name}
							</>
						)}{" "}
						at the head of {hasText(branch) ? <code>{branch}</code> : "the default branch"}. When it
						differs from the current version, it becomes the next version. The practice is then
						read-only here: you change its definition and group in that folder.
					</AlertDialogDescription>
				</AlertDialogHeader>
				{request?.phase === "refused" && (
					<Alert variant="destructive" role="alert">
						<CircleAlert />
						<AlertTitle>We could not move it to the repository</AlertTitle>
						<AlertDescription>
							<p>{request.reason}</p>
							{request.problems.length > 0 && (
								<ul aria-label="Problems in the folder" className="mt-2 list-disc space-y-1 pl-4">
									{request.problems.map((problem, index) => (
										// Replaced whole on each refusal; one place can have two problems.
										<li key={`${index}:${problemPlace(problem)}`}>
											{hasText(problem.file) && (
												<>
													<code className="break-all">{problemPlace(problem)}</code>:{" "}
												</>
											)}
											{problem.message}
										</li>
									))}
								</ul>
							)}
						</AlertDescription>
					</Alert>
				)}
				<AlertDialogFooter>
					<AlertDialogCancel disabled={pending}>Cancel</AlertDialogCancel>
					<AlertDialogAction pending={pending} disabled={pending} onClick={onConfirm}>
						{pending ? "Moving it to the repository…" : "Move to repository"}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
