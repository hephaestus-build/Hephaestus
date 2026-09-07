import { ClipboardList } from "lucide-react";
import { useState } from "react";

import type { Survey } from "@/api/types.gen";
import { ProductSurveyForm } from "@/components/feedback/ProductSurveyForm";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogHeader,
	DialogTitle,
	DialogTrigger,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";

interface ProductSurveyInvitationsProps {
	surveys: Survey[];
	isLoading: boolean;
	loadError: boolean;
	isPending: boolean;
	error?: string;
	onRetry: () => void;
	onSelect: () => void;
	onSubmit: (surveyId: string, answers: Record<string, string>) => Promise<boolean>;
	onDismiss: (surveyId: string) => Promise<boolean>;
}

function focusListTitle(node: HTMLHeadingElement | null) {
	node?.focus();
}

export function ProductSurveyInvitations({
	surveys,
	isLoading,
	loadError,
	isPending,
	error,
	onRetry,
	onSelect,
	onSubmit,
	onDismiss,
}: ProductSurveyInvitationsProps) {
	const [open, setOpen] = useState(false);
	const [selected, setSelected] = useState<Survey>();
	const [drafts, setDrafts] = useState<Record<string, Record<string, string>>>({});
	return (
		<Dialog open={open} onOpenChange={setOpen}>
			<DialogTrigger
				render={
					<Button
						variant="ghost"
						size="sm"
						aria-label={`Product surveys${surveys.length ? ` (${surveys.length} available)` : ""}`}
					/>
				}
			>
				<ClipboardList />
				<span className="hidden sm:inline">Surveys</span>
				{surveys.length > 0 && (
					<span className="rounded-full bg-muted px-1.5 text-xs tabular-nums">
						{surveys.length}
					</span>
				)}
			</DialogTrigger>
			<DialogContent className="sm:max-w-lg">
				{selected ? (
					<ProductSurveyForm
						key={selected.id}
						survey={selected}
						answers={drafts[selected.id] ?? {}}
						onAnswersChange={(answers) => setDrafts({ ...drafts, [selected.id]: answers })}
						isSubmitting={isPending}
						error={error}
						onBack={() => {
							setSelected(undefined);
							onSelect();
						}}
						onSubmit={async (answers) => {
							if (await onSubmit(selected.id, answers)) {
								setSelected(undefined);
								setDrafts((current) => {
									const next = { ...current };
									delete next[selected.id];
									return next;
								});
								setOpen(false);
							}
						}}
						onDismiss={async () => {
							if (await onDismiss(selected.id)) setSelected(undefined);
						}}
					/>
				) : (
					<>
						<DialogHeader>
							<DialogTitle ref={focusListTitle} tabIndex={-1}>
								Product surveys
							</DialogTitle>
							<DialogDescription>
								Help improve Hephaestus when it suits you. Participation is optional.
							</DialogDescription>
						</DialogHeader>
						{isLoading ? (
							<div aria-label="Loading surveys" className="space-y-3">
								<Skeleton className="h-20 w-full" />
								<Skeleton className="h-20 w-full" />
							</div>
						) : loadError ? (
							<div className="space-y-3">
								<p>Surveys couldn't be loaded.</p>
								<Button variant="outline" onClick={onRetry}>
									Try again
								</Button>
							</div>
						) : surveys.length === 0 ? (
							<p className="text-muted-foreground">
								You're all caught up. You can still send product feedback at any time.
							</p>
						) : (
							<ul className="space-y-3">
								{surveys.map((survey) => (
									<li key={survey.id} className="space-y-2 rounded-lg border p-3">
										<h3 className="font-medium break-words">{survey.title}</h3>
										<p className="text-sm text-muted-foreground break-words">
											{survey.description}
										</p>
										<div className="flex items-center justify-between gap-3">
											<span className="text-xs text-muted-foreground">
												{survey.questions.length}{" "}
												{survey.questions.length === 1 ? "question" : "questions"}
											</span>
											<Button
												variant="outline"
												size="sm"
												onClick={() => {
													onSelect();
													setSelected(survey);
												}}
											>
												Take survey
											</Button>
										</div>
									</li>
								))}
							</ul>
						)}
					</>
				)}
			</DialogContent>
		</Dialog>
	);
}
