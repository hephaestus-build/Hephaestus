import { useSpinDelay } from "spin-delay";

/**
 * Whether a pending control shows its spinner: after a second, then for at least half a second, so a
 * quick action never flashes one (`webapp/DESIGN.md` § Loading and errors). `ssr: false`: with the
 * default, a control that mounts pending shows its spinner at once.
 */
export function usePendingSpinner(pending: boolean): boolean {
	return useSpinDelay(pending, { delay: 1000, minDuration: 500, ssr: false });
}
