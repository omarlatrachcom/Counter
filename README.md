# Counter

An Android workout timer app built with Kotlin and Jetpack Compose.

The app currently includes three main flows:

- `Counter`: a rep-based counter with optional voice counting, optional rest between reps, and a finish sound.
- `Warm Up Timer`: a music-backed warm-up flow that can transition into a long-running silent timer.
- `Sun Timer`: a 29-minute or 20-minute music-backed countdown with milestone beeps.

## Features

### Counter

- Red setup and session screen
- Reps input from `1` to `20`
- Rep duration choices: `10`, `30`, or `60` seconds, plus a custom `1` to `6` minute field
- Optional silent mode
- Optional rest between reps
- Spoken count audio using `count_01` to `count_20`
- `beep.wav` for rest transitions when sound is enabled
- `finished.mp3` when the set is complete
- Pause/resume and cancel support during a running set

### Warm Up Timer

- Pink dedicated screen
- `Start Warm Up` begins background music and a silent warm-up timer
- `Start Timer` freezes the warm-up time and starts the real timer
- No spoken minute announcements after `Start Timer`
- At minute `5`, the app plays `finished.mp3`
- After minute `5`, the timer and music continue until the music ends or the user presses `Stop`
- If the music track ends first, the session stops automatically and keeps the measured times
- Uses a foreground service so the warm-up flow is more reliable during long runs

### Sun Timer

- Yellow dedicated screen
- `Start Timer` begins the selected 29-minute or 20-minute countdown and plays `music_2.mp3`
- The 29-minute timer plays `beep.wav` after 15 and 22 minutes from the start
- The 20-minute timer plays `beep.wav` after 10 and 15 minutes from the start
- Plays `finished.mp3` when the timer reaches zero, then stops the music
- Stop/resume pauses and resumes both the timer and music
- Cancel asks for confirmation before stopping the timer and music

## Audio Files

The app expects audio files in:

`app/src/main/res/raw/`

Current filenames used by the app:

- `count_01.mp3` ... `count_20.mp3`
- `beep.wav`
- `finished.mp3`
- `music.mp3`
- `music_2.mp3`

Android resource naming rules matter:

- lowercase only
- numbers and underscores only
- no spaces
- no hyphens

## Tech Stack

- Kotlin
- Jetpack Compose
- Android foreground service for long-running warm-up audio/timing
- `MediaPlayer` for local audio playback

## Build

Build a debug APK with:

```bash
./gradlew :app:assembleDebug
```

The generated APK will be at:

`app/build/outputs/apk/debug/app-debug.apk`

## Install on a Device

If `adb` is available:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project Structure

- `app/src/main/java/com/omarlatrach/counter/MainActivity.kt`
  Main Compose UI and screen flow
- `app/src/main/java/com/omarlatrach/counter/WarmUpForegroundService.kt`
  Foreground service for warm-up music, long-running timing, and the 5-minute finish sound
- `app/src/main/res/raw/`
  App audio assets
- `app/src/main/res/drawable/` and `drawable-v24/`
  Launcher icon assets

## Notes

- The warm-up session is designed to continue more reliably than a plain in-screen timer because it is driven by a foreground service.
- The launcher icon is a custom adaptive icon created for this app.
