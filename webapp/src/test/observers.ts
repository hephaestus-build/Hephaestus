import { vi } from "vitest";

/**
 * A stand-in for the DOM observers jsdom does not ship — `ResizeObserver`, which Base UI's anchor
 * positioning needs, and `IntersectionObserver`, which the landing page uses for decorative motion.
 * It never fires a callback. `vi.fn()` rather than an empty method because `no-empty-function` and
 * `class-methods-use-this` both reject a method that does nothing.
 */
export class ObserverStub {
	observe = vi.fn();
	unobserve = vi.fn();
	disconnect = vi.fn();
}
