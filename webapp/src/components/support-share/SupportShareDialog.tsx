import { CheckCircle2Icon } from "lucide-react";
import { useId } from "react";

import type { SupportShare, SupportSharePreview } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { formatDateTime } from "@/lib/dates";

import { SupportShareContent } from "./SupportShareContent";

export type SupportSharePreviewState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; preview: SupportSharePreview };

export interface SupportShareDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	/** Exactly what the recipient would read, for the choice below. */
	preview: SupportSharePreviewState;
	/** Whether the share shows the question and the answer, or only how Hephaestus ran them. */
	includeText: boolean;
	onIncludeTextChange: (includeText: boolean) => void;
	recipientId: string | undefined;
	onRecipientChange: (accountId: string) => void;
	/** The messages changed after the preview, so the share was refused and the preview read again. */
	changed?: boolean;
	shareError?: unknown;
	sharing: boolean;
	onShare: () => void;
	/** The share once made: who reads it, and until when. */
	shared?: SupportShare;
}

/**
 * The person shares one of their questions with one instance admin. The preview is the exact page
 * the admin reads, and nothing is shared until the person picks the admin and confirms.
 */
export function SupportShareDialog({
	open,
	onOpenChange,
	preview,
	includeText,
	onIncludeTextChange,
	recipientId,
	onRecipientChange,
	changed = false,
	shareError,
	sharing,
	onShare,
	shared,
}: SupportShareDialogProps) {
	const id = useId();
	const ready = preview.status === "ready" ? preview.preview : undefined;
	const recipients = ready?.recipients ?? [];
	const items = recipients.map((recipient) => ({
		value: recipient.accountId,
		label: recipient.displayName,
	}));
	const canShare = ready !== undefined && recipientId !== undefined && !sharing;
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent className="sm:max-w-xl">
				<DialogHeader className="pr-8">
					<DialogTitle>Share with support</DialogTitle>
					<DialogDescription>
						One instance admin reads exactly what you see below, for {ready?.validForDays ?? 7}{" "}
						days. You can revoke it in Settings, under Support shares.
					</DialogDescription>
				</DialogHeader>
				<DialogBody className="space-y-5">
					{shared ? (
						<Alert>
							<CheckCircle2Icon aria-hidden />
							<AlertTitle>Shared with {shared.recipient.displayName}</AlertTitle>
							<AlertDescription>
								They can read it until {formatDateTime(shared.expiresAt)}. Settings shows each time
								they read it.
							</AlertDescription>
						</Alert>
					) : (
						<>
							<FieldGroup>
								<Field orientation="horizontal">
									<FieldContent>
										<FieldLabel htmlFor={`${id}-text`}>Include the messages</FieldLabel>
										<FieldDescription>
											Off: the admin sees only how Hephaestus ran this question, with no words. On:
											they also read your question and the answer. An answer can quote work that the
											admin cannot open.
										</FieldDescription>
									</FieldContent>
									<Switch
										id={`${id}-text`}
										checked={includeText}
										onCheckedChange={onIncludeTextChange}
										pending={preview.status === "loading"}
									/>
								</Field>
							</FieldGroup>
							{changed && (
								<Alert>
									<AlertTitle>The messages changed</AlertTitle>
									<AlertDescription>
										Look at the preview again. Nothing was shared.
									</AlertDescription>
								</Alert>
							)}
							<section aria-labelledby={`${id}-preview`} className="space-y-2">
								<h3 id={`${id}-preview`} className="text-sm font-medium">
									What the admin reads
								</h3>
								{preview.status === "loading" && <Skeleton className="h-32 w-full rounded-lg" />}
								{preview.status === "error" && (
									<QueryErrorAlert
										error={preview.error}
										title="We could not show the preview"
										onRetry={preview.onRetry}
									/>
								)}
								{ready && (
									<SupportShareContent turn={ready.turn} messages={ready.messages} asker="You" />
								)}
							</section>
							{ready && recipients.length === 0 && (
								<p className="text-sm text-muted-foreground">
									This instance has no admin to share with.
								</p>
							)}
							{recipients.length > 0 && (
								<FieldGroup>
									<Field orientation="responsive">
										<FieldContent>
											<FieldLabel id={`${id}-recipient-label`} htmlFor={`${id}-recipient`}>
												Who reads it
											</FieldLabel>
											<FieldDescription>
												One instance admin. Nobody else can read it.
											</FieldDescription>
										</FieldContent>
										<Select
											items={items}
											value={recipientId ?? null}
											onValueChange={(value) => {
												if (value !== null) {
													onRecipientChange(value);
												}
											}}
										>
											<SelectTrigger id={`${id}-recipient`} className="w-full @md/field-group:w-56">
												<SelectValue placeholder="Choose an admin" />
											</SelectTrigger>
											<SelectContent aria-labelledby={`${id}-recipient-label`}>
												{items.map((item) => (
													<SelectItem key={item.value} value={item.value}>
														{item.label}
													</SelectItem>
												))}
											</SelectContent>
										</Select>
									</Field>
								</FieldGroup>
							)}
							{shareError !== undefined && (
								<QueryErrorAlert error={shareError} title="We could not share this question" />
							)}
						</>
					)}
				</DialogBody>
				<DialogFooter>
					{shared ? (
						<Button variant="outline" onClick={() => onOpenChange(false)}>
							Done
						</Button>
					) : (
						<>
							<Button variant="outline" onClick={() => onOpenChange(false)}>
								Cancel
							</Button>
							<Button pending={sharing} disabled={!canShare} onClick={onShare}>
								Share
							</Button>
						</>
					)}
				</DialogFooter>
			</DialogContent>
		</Dialog>
	);
}
