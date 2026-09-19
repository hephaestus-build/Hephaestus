import { render, screen } from "@testing-library/react";
import { assert, describe, expect, it } from "vitest";

import { MoneyCell } from "./MoneyCell";

describe("MoneyCell", () => {
	it("announces the figure without the cents it pads the column with", () => {
		render(
			<table>
				<tbody>
					<tr>
						<td>
							<MoneyCell>$0</MoneyCell>
						</td>
					</tr>
				</tbody>
			</table>,
		);

		// The pad is `aria-hidden`: announcing "$0.00" would be a different amount of money.
		screen.getByRole("row", { name: "$0" });
	});

	it("leaves a figure that already prints its cents exactly as it is", () => {
		// Padding a figure that already ends in cents would render "$4.50.00".
		render(
			<table>
				<tbody>
					<tr>
						<td>
							<MoneyCell>$4.50</MoneyCell>
						</td>
						<td>
							<MoneyCell>{"<$0.01"}</MoneyCell>
						</td>
					</tr>
				</tbody>
			</table>,
		);

		const [cents, bound] = screen.getAllByRole("cell");
		assert(cents);
		assert(bound);
		expect(cents.textContent).toBe("$4.50");
		expect(bound.textContent).toBe("<$0.01");
	});
});
