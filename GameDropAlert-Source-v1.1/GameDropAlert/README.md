# GameDrop Alert (Android)

An Android app that checks Lenovo Gaming Community and Alienware Arena every 15 minutes and sends local notifications for newly detected game/key listing links.

## Lenovo queue reminders

When a newly detected Lenovo listing exposes a readable queue/drop start date and time, the app schedules two local reminders:
- 24 hours before the queue opens.
- 2 hours before the queue opens.

If a later scan detects that the published event time changed, the pending reminders are replaced with reminders for the new time. Reminders that are already in the past are cancelled/skipped. The app uses the phone's local timezone. Android WorkManager may deliver reminders later than the exact requested minute under battery saver/Doze restrictions.

The date parser currently recognizes common English date/time formats and machine-readable `datetime`, `data-date`, and `data-start` attributes. If Lenovo publishes the date only inside JavaScript, images, or an unsupported format, the app cannot schedule reminders for that listing until its parser is adapted.

## Monitoring limitations

- WorkManager's 15-minute periodic interval is a minimum, not an exact schedule. Android battery optimization/doze can delay checks.
- This version reads public HTML with Jsoup. If either site moves listings into client-rendered scripts, changes markup, blocks automated requests, or requires a signed-in session, the parser will need adapting. It does not sign in to either account.
- The first successful scan establishes a baseline; existing links are not announced as newly added. Lenovo entries with a readable future date can still have reminders scheduled during the baseline scan.
- ARP cost is shown only when the number is present in the listing card's accessible text; it is not guaranteed for every giveaway.
- A site update is treated as a new candidate listing when a matching link URL appears; generic homepage text changes are not yet reported.

## Build APK

The repository includes a GitHub Actions workflow. Upload this project to a GitHub repository, then open **Actions → Build Android APK → Run workflow**. Download the `GameDropAlert-debug-apk` artifact; it contains `app-debug.apk`.

For local builds, use JDK 17 and Android SDK platform 35, then run `gradle assembleDebug`.
