import { useSpinDelay } from "spin-delay";

export const PENDING_SPINNER_DELAY_MS = 1000;
export const PENDING_SPINNER_MIN_MS = 500;

/**
 * Whether a pending control shows its spinner: after a second, then for at least half a second, so a
 * quick action never flashes one (`webapp/DESIGN.md` § Loading and errors). `ssr: false`: with the
 * default, a control that mounts pending shows its spinner at once.
 */
export function usePendingSpinner(pending: boolean): boolean {
	return useSpinDelay(pending, {
		delay: PENDING_SPINNER_DELAY_MS,
		minDuration: PENDING_SPINNER_MIN_MS,
		ssr: false,
	});
}
