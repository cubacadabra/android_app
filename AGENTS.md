this app is an android port from ../ios_app

make sure ui changes work in dark or light mode

For every new Jetpack Compose screen, use a theme-aware root container such as
`Surface` with `MaterialTheme.colorScheme.background` and
`MaterialTheme.colorScheme.onBackground`. Do not leave a screen rooted in a
bare `Column` or other layout container, since it can render with the wrong
background in dark mode.

you can work in ../backend too if needed

After making code changes do not run gradlew or adb let me test changes in android studio.

## Android architecture guardrails

Use Kotlin + Jetpack Compose with a deliberately simple architecture.

- Keep screen-specific, short-lived UI state in the composable.
- Use a ViewModel when state is shared across screens, must survive recreation, or owns lifecycle-scoped asynchronous work.
- Call a small service directly when that is clearer.
- Add repositories, use cases, interfaces, or dependency-injection machinery only when there are multiple real implementations, multiple data sources, substantial domain logic, or a clear platform/testing boundary.
- Do not add abstraction layers merely to forward calls.
- Prefer extending the existing app-level state holder for cross-screen features instead of creating parallel state systems.
- Do not restructure working code into a different architecture unless the task specifically requires it.
