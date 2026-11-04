# AI Subtitle Translator 1.1.1: release notes

Developer: aivicventures, https://aivicventures.com. Package
`com.aivicventures.subtitletranslator`, versionCode 4. Signed with the same release key as
1.0.0 and 1.1.0, so it installs over either and keeps recent movies and saved subtitles.

## Removed: Live Floating Subtitles

The live overlay added in 1.1.0 did not work as expected and has been removed: the screens,
the service, the overlay, its settings and its native changes. The player is the same as in
1.0.0.

## Permissions

The permissions 1.1.0 added are gone: display over other apps, record audio, foreground
service and notifications. The app again asks for no permissions and has no INTERNET
permission.

## Other changes

Lines dropped as hallucinations are no longer written to the system log unless a developer
turns that on over USB (`adb shell setprop log.tag.WhisperAsr DEBUG`).

The 1.1.0 code remains in the git history, at commit 43ed960.
