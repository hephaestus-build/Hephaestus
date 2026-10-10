import { useEffect } from "react";

import { getProviderSlug, type ProviderType } from "@/lib/provider/provider-terms";

/**
 * Paints the `--color-provider-*` tokens for the workspace's provider from the document root, like
 * the theme's `.dark`. A scope on an element inside the app would miss every drawer, popover and
 * tooltip, which render in portals on `<body>`, and they would read GitHub's colours on GitLab.
 */
export function useProviderColors(provider: ProviderType) {
	useEffect(() => {
		const root = window.document.documentElement;
		root.dataset.provider = getProviderSlug(provider);
		return () => {
			delete root.dataset.provider;
		};
	}, [provider]);
}
