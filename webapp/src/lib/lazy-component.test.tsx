import { render, screen } from "@testing-library/react";
import { Suspense } from "react";
import { describe, it } from "vitest";

import { type LazyComponent, lazyComponent, useLazyComponent } from "./lazy-component";

interface GreetingProps {
	name: string;
}

function Greeting({ name }: GreetingProps) {
	return <p>Hello {name}</p>;
}

function Host({ greeting }: { greeting: LazyComponent<GreetingProps> }) {
	const Component = useLazyComponent(greeting);
	return (
		<Suspense fallback={<p>Loading</p>}>
			<Component name="Ada" />
		</Suspense>
	);
}

describe("lazyComponent", () => {
	it("renders in the same pass once its code has loaded", async () => {
		const greeting = lazyComponent<GreetingProps>(async () => Greeting);
		await greeting.preload();

		render(<Host greeting={greeting} />);

		screen.getByText("Hello Ada");
	});

	it("shows the fallback until code it has not loaded arrives", async () => {
		const greeting = lazyComponent<GreetingProps>(async () => Greeting);

		render(<Host greeting={greeting} />);

		screen.getByText("Loading");
		await screen.findByText("Hello Ada");
	});
});
