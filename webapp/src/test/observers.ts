import { vi } from "vitest";

/**
 * A stand-in for the DOM observers jsdom does not ship — `ResizeObserver`, which Base UI's anchor
 * positioning needs, and `IntersectionObserver`, which the landing page uses for decorative motion.
 * It never fires a callback.
 */
export class ObserverStub {
	observe = vi.fn();
	unobserve = vi.fn();
	disconnect = vi.fn();
}
