# Android accessibility: official behavior and limits

This note captures the official platform facts used when designing Fluwy Bot's opt-in cross-app accessibility feature. It does not claim that arbitrary third-party app flows are universally automatable.

## Permissions and service lifecycle

- Android binds an `AccessibilityService` only after the user explicitly enables it in Android's Accessibility settings. The service must declare `android.permission.BIND_ACCESSIBILITY_SERVICE` and the `android.accessibilityservice.AccessibilityService` intent action. Source: [AccessibilityService API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).
- Reading a window's accessible tree requires `canRetrieveWindowContent` in the service metadata. Android exposes the active window as `AccessibilityWindowInfo` and `AccessibilityNodeInfo` objects; the tree may be incomplete or stale as apps update their UI. Source: [AccessibilityService API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).
- `AccessibilityNodeInfo.performAction()` is restricted to calls made by an `AccessibilityService`. Nodes can provide click, text, scroll, and other actions, but the source app must expose compatible semantics/actions. Source: [AccessibilityNodeInfo API reference](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo).
- Gesture injection uses `AccessibilityService.dispatchGesture()` and requires the `canPerformGestures` service capability. Android notes that dispatching a gesture cancels gestures already in progress. Source: [AccessibilityService API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).

## Distinct Android capabilities

- Becoming the default voice assistant is a separate system role (`RoleManager.ROLE_ASSISTANT`) and requires user consent. `VoiceInteractionService` supports voice-interaction sessions; this role does not itself provide arbitrary control of other apps' UI. Sources: [RoleManager API reference](https://developer.android.com/reference/android/app/role/RoleManager) and [VoiceInteractionService API reference](https://developer.android.com/reference/android/service/voice/VoiceInteractionService).
- `PackageManager.getLaunchIntentForPackage()` returns an intent for a package's front-door activity. Launching an app does not grant access to its internal UI; that is a separate capability. Source: [PackageManager API reference](https://developer.android.com/reference/android/content/pm/PackageManager).

## Reliability, privacy, and distribution

- Accessibility APIs expose only what an app makes available through its accessibility tree, and controls can be missing, custom, stale, or behaviorally different across app versions and Android devices. Therefore Fluwy can provide an inspect-act-verify loop, not a guarantee that every app/task will work.
- The implementation must not treat accessibility nodes as permission for screenshots. It should inspect only on explicit agent tool calls, avoid automatic screen capture, avoid retaining screen trees beyond an action, mask password fields, and report bounded results to the task that requested them.
- The Android speech `RecognitionListener.onRmsChanged(rmsdB)` callback reports a sound-level change, but Android gives no guarantee that it will be called. The selected speech provider also determines recognition behavior; app-side gating is only an additional filter, not a provider-independent sensitivity control. Source: [RecognitionListener API reference](https://developer.android.com/reference/android/speech/RecognitionListener).
- Google Play's current [AccessibilityService API policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en) sets strict requirements on automation use and restricts autonomous apps that initiate, plan, and execute actions unless they qualify as disability-focused accessibility tools. This requested build is for private/sideloaded distribution, not a Play submission; re-evaluate before any public-store release.
