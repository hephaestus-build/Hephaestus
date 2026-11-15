import { type ComponentType, lazy, useState } from "react";

export interface LazyComponent<P> {
	/** Loads the code ahead of its first use, as a test, a story or a page that will need it does. */
	preload: () => Promise<ComponentType<P>>;
	/** The loaded component, or the `React.lazy` one while the code has not loaded yet. */
	current: () => ComponentType<P>;
}

/**
 * A component whose code loads on first use, like `React.lazy`. Once that code has loaded, it renders
 * in the same pass: a `React.lazy` component suspends on its own first render even when its module
 * is already loaded, so whatever reads the page right after a render would find the fallback.
 */
export function lazyComponent<P extends object>(
	load: () => Promise<ComponentType<P>>,
): LazyComponent<P> {
	let loaded: ComponentType<P> | undefined;
	const preload = async (): Promise<ComponentType<P>> => {
		loaded ??= await load();
		return loaded;
	};
	const Lazy = lazy(async () => ({ default: await preload() }));
	return { preload, current: () => loaded ?? Lazy };
}

/** The component to render, chosen once per mount: a switch from the lazy one to the loaded one would remount it. */
export function useLazyComponent<P>(component: LazyComponent<P>): ComponentType<P> {
	return useState(component.current)[0];
}
