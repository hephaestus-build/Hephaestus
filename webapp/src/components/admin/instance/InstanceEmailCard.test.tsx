import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { InstanceEmailCard } from "./InstanceEmailCard";

afterEach(() => {
	cleanup();
	vi.useRealTimers();
});

describe("test email loading feedback", () => {
	it("disables sending immediately without flashing feedback for a fast request", async () => {
		vi.useFakeTimers();
		const onSendTest = vi.fn();
		const { rerender } = render(<InstanceEmailCard isPending={false} onSendTest={onSendTest} />);

		rerender(<InstanceEmailCard isPending onSendTest={onSendTest} />);
		const send = screen.getByRole<HTMLButtonElement>("button", { name: "Send test email" });
		expect(send.disabled).toBe(true);
		fireEvent.click(send);
		expect(onSendTest).not.toHaveBeenCalled();

		await act(() => vi.advanceTimersByTimeAsync(999));
		expect(screen.queryByRole("button", { name: "Sending…" })).toBeNull();
		expect(send.querySelector(".animate-spin")).toBeNull();
		rerender(<InstanceEmailCard isPending={false} onSendTest={onSendTest} />);
		expect(send.disabled).toBe(false);

		await act(() => vi.advanceTimersByTimeAsync(1000));
		expect(screen.queryByRole("button", { name: "Sending…" })).toBeNull();
		expect(send.querySelector(".animate-spin")).toBeNull();
	});

	it("delays slow-request feedback and keeps sending disabled for its minimum visible duration", async () => {
		vi.useFakeTimers();
		const onSendTest = vi.fn();
		const { rerender } = render(<InstanceEmailCard isPending={false} onSendTest={onSendTest} />);

		rerender(<InstanceEmailCard isPending onSendTest={onSendTest} />);
		await act(() => vi.advanceTimersByTimeAsync(999));
		expect(screen.queryByRole("button", { name: "Sending…" })).toBeNull();
		await act(() => vi.advanceTimersByTimeAsync(1));
		const sending = screen.getByRole<HTMLButtonElement>("button", { name: "Sending…" });
		expect(sending.disabled).toBe(true);
		expect(sending.querySelector(".animate-spin")).not.toBeNull();

		await act(() => vi.advanceTimersByTimeAsync(200));
		rerender(<InstanceEmailCard isPending={false} onSendTest={onSendTest} />);
		fireEvent.click(sending);
		expect(onSendTest).not.toHaveBeenCalled();
		await act(() => vi.advanceTimersByTimeAsync(299));
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Sending…" }).disabled).toBe(true);

		expect(sending.querySelector(".animate-spin")).not.toBeNull();
		await act(() => vi.advanceTimersByTimeAsync(1));
		const send = screen.getByRole<HTMLButtonElement>("button", { name: "Send test email" });
		expect(send.disabled).toBe(false);
		expect(send.querySelector(".animate-spin")).toBeNull();
		fireEvent.click(send);
		expect(onSendTest).toHaveBeenCalledExactlyOnceWith(undefined);
	});
});
