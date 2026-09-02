this app is an android port from ../ios_app

make sure ui changes work in dark or light mode

you can work in ../backend too if needed

any changes to backend have to be backwards compatible with older clients

do not write any tests 

After making code changes do not run gradlew or adb let me test changes in android studio.

when you are done editing the code just reply "-----------------------------> DONE!" no need to generate a summary of what was done.

## Android architecture guardrails

Use Kotlin + Jetpack Compose with a deliberately simple architecture.

- Keep screen-specific, short-lived UI state in the composable.
- Use a ViewModel when state is shared across screens, must survive recreation, or owns lifecycle-scoped asynchronous work.
- Call a small service directly when that is clearer.
- Add repositories, use cases, interfaces, or dependency-injection machinery only when there are multiple real implementations, multiple data sources, substantial domain logic, or a clear platform/testing boundary.
- Do not add abstraction layers merely to forward calls.
- Prefer extending the existing app-level state holder for cross-screen features instead of creating parallel state systems.
- Do not restructure working code into a different architecture unless the task specifically requires it.
