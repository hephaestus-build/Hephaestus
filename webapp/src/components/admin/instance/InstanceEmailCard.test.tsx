import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { InstanceEmailCard } from "./InstanceEmailCard";

afterEach(() => {
	cleanup();
	vi.useRealTimers();
});

const spinner = (button: HTMLElement) => button.querySelector("[data-slot=spinner]");

describe("test email loading feedback", () => {
	it("says Sending… and ignores a press at once, with no spinner for a fast request", async () => {
		vi.useFakeTimers();
		const onSendTest = vi.fn();
		const { rerender } = render(<InstanceEmailCard isPending={false} onSendTest={onSendTest} />);

		rerender(<InstanceEmailCard isPending onSendTest={onSendTest} />);
		const sending = screen.getByRole("button", { name: "Sending…" });
		expect(sending.getAttribute("aria-disabled")).toBe("true");
		fireEvent.click(sending);
		expect(onSendTest).not.toHaveBeenCalled();

		await act(async () => vi.advanceTimersByTimeAsync(999));
		expect(spinner(sending)).toBeNull();
		rerender(<InstanceEmailCard isPending={false} onSendTest={onSendTest} />);
		const send = screen.getByRole("button", { name: "Send test email" });
		expect(send.getAttribute("aria-disabled")).toBeNull();

		await act(async () => vi.advanceTimersByTimeAsync(1000));
		expect(spinner(send)).toBeNull();
	});

	it("shows the spinner after a second for a slow request, and sends again once it settles", async () => {
		vi.useFakeTimers();
		const onSendTest = vi.fn();
		const { rerender } = render(<InstanceEmailCard isPending={false} onSendTest={onSendTest} />);

		rerender(<InstanceEmailCard isPending onSendTest={onSendTest} />);
		await act(async () => vi.advanceTimersByTimeAsync(999));
		const sending = screen.getByRole("button", { name: "Sending…" });
		expect(spinner(sending)).toBeNull();
		await act(async () => vi.advanceTimersByTimeAsync(1));
		expect(spinner(sending)).not.toBeNull();

		rerender(<InstanceEmailCard isPending={false} onSendTest={onSendTest} />);
		await act(async () => vi.advanceTimersByTimeAsync(500));
		const send = screen.getByRole("button", { name: "Send test email" });
		expect(spinner(send)).toBeNull();
		fireEvent.click(send);
		expect(onSendTest).toHaveBeenCalledExactlyOnceWith(undefined);
	});
});
