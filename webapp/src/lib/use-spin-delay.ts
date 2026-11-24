/**
 * Owned from: https://github.com/smeijer/spin-delay/blob/f76e32e5f5090111eff4ee7e70b8611b60bf5a1c/src/index.ts
 * Package: pkg:npm/spin-delay@2.0.1
 * License: MIT
 * Changes: Keeps the state machine and timing defaults, types the timer and omits an unused export.
 *
 * MIT License
 *
 * Copyright (c) 2020 Stephan Meijer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
import { useEffect, useRef, useState } from "react";

interface SpinDelayOptions {
	delay?: number;
	minDuration?: number;
	ssr?: boolean;
}

type State = "IDLE" | "DELAY" | "DISPLAY" | "EXPIRE";
const defaults = { delay: 500, minDuration: 200, ssr: true };

export function useSpinDelay(loading: boolean, options?: SpinDelayOptions): boolean {
	const { delay, minDuration, ssr } = { ...defaults, ...options };
	const [isFirstRender, setIsFirstRender] = useState(true);
	useEffect(() => {
		// oxlint-disable-next-line react/set-state-in-effect -- Match the server on the first render, then use the client delay.
		setIsFirstRender(false);
	}, []);

	const isSSR = isFirstRender && ssr;
	const [state, setState] = useState<State>(isSSR && loading ? "DISPLAY" : "IDLE");
	const timeout = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
	useEffect(() => {
		if (loading && (state === "IDLE" || isSSR)) {
			clearTimeout(timeout.current);
			timeout.current = setTimeout(
				() => {
					timeout.current = setTimeout(() => setState("EXPIRE"), minDuration);
					setState("DISPLAY");
				},
				isSSR ? 0 : delay,
			);
			if (!isSSR) {
				// oxlint-disable-next-line react/set-state-in-effect -- The DELAY state keeps the timer from rearming while the request is pending.
				setState("DELAY");
			}
		}
		if (!loading && state !== "DISPLAY") {
			clearTimeout(timeout.current);
			setState("IDLE");
		}
	}, [loading, state, delay, minDuration, isSSR]);

	useEffect(() => () => clearTimeout(timeout.current), []);
	return state === "DISPLAY" || state === "EXPIRE";
}
