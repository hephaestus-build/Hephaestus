## How to do it

- For each access that your change adds, add any missing usage key. The authorization API and the iOS version decide which key.
- Put the key where the project keeps them: in `Info.plist`, in `project.yml`, or in the target’s build settings.
- Write a plain usage description that says what the app does with the access. Instead of “Camera access is needed”, write “Scan receipts to add expenses.”
- Ask for access from the feature that needs it, not at launch.
- Before you add a key, check that the system needs one. Notifications need no usage description.

## When it does not apply

- Your change adds no access that needs the user’s permission. It adds no use of location, the photo library, camera or microphone capture, notifications, calendars, contacts, health data or tracking. It adds no authorization request.
- Your change only adds the system photo picker, such as `PhotosPicker` or `PHPickerViewController`. It needs no access to the whole library.
- Your change only imports a framework. An import alone asks for no access.

It still applies when the first screen is the feature that needs the permission.

## Common mistakes

- Adding one fixed key for each framework that you import. Instead, read which authorization call the code makes and which iOS version the app targets.
- Asking for every permission when the app starts. The user has not reached the feature, so they cannot see why you ask. Instead, move each request to its feature.
- Moving a request away from a first screen that needs the access. If the app opens on a map, asking for location when the map appears is the point of use. Instead, keep the request there.
- Writing a placeholder such as “Needed for the app”, or leaving the text empty. Instead, name the task that the access makes possible, in words the user knows.

## Sources

- [Privacy](https://developer.apple.com/design/human-interface-guidelines/privacy), Apple Human Interface Guidelines. Ask for access when people use the feature that needs it. Avoid a launch request unless the app needs the access to function.
- [Requesting access to protected resources](https://developer.apple.com/documentation/uikit/requesting-access-to-protected-resources), Apple Developer Documentation. The system asks once, and a missing purpose string can crash the app or lead to App Review rejection.
- [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/), Apple, section 5.1.1 (ii). Purpose strings must clearly and completely describe how the app uses the data.
- [Explanation beats context: the effect of timing and rationales on users’ runtime permission decisions](https://www.usenix.org/conference/usenixsecurity21/presentation/elbitar), Elbitar and others, 2021. In a study with Android mockup apps, requests in context and requests with a reason were each granted more often.
