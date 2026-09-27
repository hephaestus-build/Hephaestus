/**
 * What to do with this installation's push registration when the app returns to the foreground or the
 * system hands it a new push token. Registration itself only ever follows the person's own choice in
 * Notifications; this keeps a registration they chose true to the device.
 *
 * - Permission revoked in the system settings: unregister, so the server stops sending to it.
 * - A new push token while registered: register again, so notifications reach the new token.
 * - Anything else: leave it, and never register a device the person did not turn on.
 */
export type Reconciliation = "unregister" | "refresh" | "none";

export function reconcileRegistration({
	registered,
	permitted,
	tokenChanged,
}: {
	registered: boolean;
	permitted: boolean;
	tokenChanged: boolean;
}): Reconciliation {
	if (!registered) {
		return "none";
	}
	if (!permitted) {
		return "unregister";
	}
	return tokenChanged ? "refresh" : "none";
}
