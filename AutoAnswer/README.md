# Auto Answer — Android call answering machine

Kotlin + Jetpack Compose app. When a call rings past your chosen delay, the app answers it,
speaks your greeting (text-to-speech), plays a beep, records the caller, hangs up, and shows
the message in an inbox with a notification.

## Build
1. Open the `AutoAnswer` folder in Android Studio (Koala or newer, JDK 17).
2. Let Gradle sync, connect a physical phone (Android 8.0+), press Run.
   (Emulators can't receive real calls.)
3. Open the app, flip the switch, and grant the permissions.

## How it works
- `AnswerService` — foreground service that listens for call state, answers with
  `TelecomManager.acceptRingingCall()`, runs greeting → beep → `MediaRecorder`, enforces
  max length / silence hang-up, hangs up with `TelecomManager.endCall()`.
- `MessageStore` — audio files + JSON index in app-private storage.
- `MainActivity` — Compose UI: on/off switch, inbox with playback, settings.

## Known Android limits
- Third-party apps cannot inject audio into, or capture, the call stream itself. The app puts the
  call on speakerphone so the greeting is heard by the caller and the caller is picked up by the
  microphone acoustically. Quality depends on the phone; some manufacturers mute the mic during
  calls for non-system apps, which yields silent recordings. Test with a second phone first.
- Android 14+ doesn't allow a microphone foreground service to start after reboot, so open the
  app once after restarting the phone.
- Check your local laws on recording calls and consider mentioning it in the greeting.
