# Handles errors

A change handles each failure it introduces: it records, rethrows or reports it.

## Not met
- A catch block returns a default value and records nothing.

```java
catch (IOException e) { return null; }
```
