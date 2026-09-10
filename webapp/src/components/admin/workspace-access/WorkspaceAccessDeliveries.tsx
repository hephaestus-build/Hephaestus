import type { WorkspaceAccessNotification } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

const kinds = {
	SUBMITTED: "Admin notification",
	DECIDED: "Decision notification",
	REMINDER: "Expiry reminder",
	EXPIRED: "Access-ended notification",
} satisfies Record<WorkspaceAccessNotification["kind"], string>;
const reasons = {
	SILENT_MODE: "Silent Mode blocks delivery",
	MAIL_NOT_CONFIGURED: "SMTP is not configured",
	NO_VERIFIED_CONTACT: "No verified email address",
	INVALID_CONTACT: "Invalid email address",
	DELIVERY_FAILED: "Delivery failed",
	REQUEST_REPLACED: "A newer request replaced this notification",
} satisfies Record<NonNullable<WorkspaceAccessNotification["reason"]>, string>;
const states = {
	PENDING: "Pending",
	SENT: "Sent",
	FAILED: "Failed",
	CANCELLED: "Cancelled",
} satisfies Record<WorkspaceAccessNotification["state"], string>;

export function WorkspaceAccessDeliveries({
	notifications,
	retryingId,
	onRetry,
}: {
	notifications: WorkspaceAccessNotification[];
	retryingId?: number;
	onRetry: (id: number) => void;
}) {
	return (
		<section className="space-y-4" aria-labelledby="access-deliveries-heading">
			<h2 id="access-deliveries-heading" className="text-xl font-semibold">
				Email delivery
			</h2>
			{notifications.length === 0 ? (
				<p>No email has been queued for this request.</p>
			) : (
				<ul className="space-y-3">
					{notifications.map((notification) => (
						<li key={notification.id} className="rounded-lg border p-4 space-y-2">
							<p className="font-medium">
								{kinds[notification.kind]} · {states[notification.state]}
							</p>
							{notification.reason && <p>{reasons[notification.reason]}</p>}
							<p className="text-sm text-muted-foreground">
								{notification.attempts ?? 0} {notification.attempts === 1 ? "attempt" : "attempts"}
								{notification.sentAt ? ` · sent ${notification.sentAt.toLocaleString()}` : ""}
							</p>
							{notification.state === "FAILED" && (
								<Button
									variant="outline"
									disabled={retryingId !== undefined}
									onClick={() => onRetry(notification.id)}
								>
									{retryingId === notification.id && <Spinner />}
									{retryingId === notification.id
										? "Scheduling retry…"
										: `Retry ${kinds[notification.kind].toLowerCase()}`}
								</Button>
							)}
						</li>
					))}
				</ul>
			)}
		</section>
	);
}
