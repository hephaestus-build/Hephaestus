## How to do it

- When a call fails and this code cannot fix it, pass the error to the caller with context. For example, instead of an empty `catch` on `saveOrder`, throw an error that names the order and wraps the original.
- When no caller can react, record the failure where a person will see it. Use a log that someone reads, a failure metric, or an error state on the screen.
- Catch the failure that you expect, and name it. `catch (UserNotFoundException e) { return false; }` says what `false` means. When code that saves, sends or signs in catches everything, keep the cause.
- When optional work can fail safely, explain the reason in the handler. No comment excuses a failed save.
- Handle each returned error, each rejected promise and each failed lookup or parse. In Rust, return a `Result`. In Go, return the error. In React, an error boundary covers render errors, and a failed request needs its own error state.

## When it does not apply

- Your hand-written code adds no call that can throw, reject or return an error. Generated or vendored files do not count.
- Your change adds no source code, such as docs, diagrams, images, or configuration only.
- Your change only ends work that a newer request replaces, or whose owner went away.
- The source proves that the guarded bad case cannot occur, such as parsing a fixed valid URL.

It still applies when your new code already handles each failure well.

## Common mistakes

- Thinking that every handler must rethrow. A log that a person will see keeps the failure visible. So does an error state on the screen. A normal empty state counts only when the failure is also logged with enough context to diagnose it.
- Catching an error and never using it, or dropping it with a shortcut such as Swift’s `try?` or Go’s `_`. Instead, pass it on, log it, or write why silence is safe, such as “the file can already be gone”.
- Leaving a TODO in place of a handler. Handle the failure before review.
- Returning `false`, null or an empty value from a catch of every exception, with no record. A caller sees that something failed, but nobody can tell a refused request from an outage or a bug. Instead, catch what you expect, and log or pass on the rest.

## Sources

- [Simple testing can prevent most critical failures](https://www.eecg.utoronto.ca/~stumm/Yuan-OSDI-14.html), Yuan and others, 2014. The study looked at 198 failures of five distributed systems. Wrong handling of signaled errors caused 92% of the catastrophic failures. About a third came from simple mistakes, such as an empty handler or a TODO.
- [Caught exceptions: not ignored](https://google.github.io/styleguide/javaguide.html#s6.2-caught-exceptions), Google Java Style Guide. It is rarely correct to do nothing with a caught exception. When it is, a comment explains why.
- [Handle errors](https://go.dev/wiki/CodeReviewComments#handle-errors), Go Code Review Comments, the Go project. Do not throw away an error with `_`. Handle it or return it.
- [CWE-390: Detection of error condition without action](https://cwe.mitre.org/data/definitions/390.html), MITRE. An attacker can use an ignored error to put the system in an unexpected state.
- [CWE-396: Declaration of catch for generic exception](https://cwe.mitre.org/data/definitions/396.html), MITRE. Catching overly broad exceptions makes error handling more complex and more likely to hold security flaws.
- [ERR00-J: Do not suppress or ignore checked exceptions](https://wiki.sei.cmu.edu/confluence/display/java/ERR00-J.+Do+not+suppress+or+ignore+checked+exceptions), SEI CERT Oracle Coding Standard for Java. A handler recovers, or it passes the failure on. Freeing a resource is one named exception.
- [Error handling](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html), OWASP Cheat Sheet Series. A global handler logs an unexpected error on the server and returns a generic response.
- [Error handling](https://doc.rust-lang.org/book/ch09-00-error-handling.html), The Rust Programming Language. Rust separates recoverable errors, with `Result`, from unrecoverable ones.
- [Catching rendering errors with an error boundary](https://react.dev/reference/react/Component#catching-rendering-errors-with-an-error-boundary), React documentation. An error boundary shows a fallback when a child throws during rendering.
