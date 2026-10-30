// oxlint-disable-next-line no-restricted-imports -- the one module that adapts sonner's `toast`.
import { toast as sonner } from "sonner";

const UNTIL_CLOSED = { duration: Number.POSITIVE_INFINITY };

/**
 * Sonner's `toast`, except that a failure stays until the reader closes it (WCAG 2.2.1). After a
 * confirm, a toast is the only report of a failed write (ADR 0027), so a timer would take it away
 * unread. A call that passes its own `duration` keeps it.
 */
export const toast = Object.assign(
	(...args: Parameters<typeof sonner>) => sonner(...args),
	sonner,
	{
		error: ((message, data) =>
			sonner.error(message, { ...UNTIL_CLOSED, ...data })) satisfies typeof sonner.error,
		warning: ((message, data) =>
			sonner.warning(message, { ...UNTIL_CLOSED, ...data })) satisfies typeof sonner.warning,
	},
);
