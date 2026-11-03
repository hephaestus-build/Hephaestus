#### 🔴 Check practice scripts that decide after a failed read

A practice script that returns its own result now needs the evidence that it asked for. It stops with the reason **Decided without its evidence** if a read failed, even if it caught the error, or if it read a record that was not captured.

This changes nothing for a script that returns `ctx.review()`. Every script in the shipped catalog does.

If you wrote a practice script that catches a read failure and then returns its own result:

1. Open the practice in the practice editor.
2. Let the review decide with `ctx.review`, or let the failure end the script.
