import { useSearchContext } from "fumadocs-ui/contexts/search";
import { HomeLayout } from "fumadocs-ui/layouts/home";
import { useEffect } from "react";

import { Provider, type ProviderProps } from "@/components/provider";
import { baseOptions } from "@/lib/layout";

/** The React island of the pages in the home layout. The content is static HTML, passed as children. */
export function Home({ children, ...props }: ProviderProps) {
	return (
		<Provider {...props}>
			<HomeLayout {...baseOptions()}>{children}</HomeLayout>
		</Provider>
	);
}

function OpenSearch() {
	const { setOpenSearch } = useSearchContext();
	useEffect(() => {
		setOpenSearch(true);
	}, [setOpenSearch]);
	return null;
}

/** `/search` is a released address (docs/released-paths.txt). It opens the search dialog. */
export function SearchHome({ children, ...props }: ProviderProps) {
	return (
		<Provider {...props}>
			<HomeLayout {...baseOptions()}>
				<OpenSearch />
				{children}
			</HomeLayout>
		</Provider>
	);
}
