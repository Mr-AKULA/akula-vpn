# Importing a subscription with a link

Olcbox accepts this URL on Android, iOS, macOS, Windows and Linux:

```text
olcbox://add?url=<percent-encoded HTTP or HTTPS subscription URL>
```

For example, `https://example.org/sub?token=a+b&device=phone` becomes:

```text
olcbox://add?url=https%3A%2F%2Fexample.org%2Fsub%3Ftoken%3Da%2Bb%26device%3Dphone
```

Generate the link in JavaScript:

```javascript
const link = "olcbox://add?url=" + encodeURIComponent(subscriptionUrl);
```

Or in Python:

```python
from urllib.parse import quote
link = "olcbox://add?url=" + quote(subscription_url, safe="")
```

Encode the complete subscription URL once, including its query and fragment. An
existing `%2F` in the subscription URL becomes `%252F` in the outer link. Literal
`+` becomes `%2B`; `&` becomes `%26`. There is no Base64 wrapper. Only the `add`
action and a single `url` parameter are supported, with a maximum link length of
16,384 characters. The subscription must use HTTP or HTTPS.

Opening a link brings Olcbox to the import dialog with the decoded URL filled in.
The user can edit it and press **Import**, or dismiss the dialog. Opening the link
does not download a subscription or start a connection. HTTP subscriptions still
require the existing **Allow insecure requests** option. Imports use the same
validation, error handling and subscription deduplication as manual imports.

Android and iOS handle both a new launch and links opened while running. The
desktop app forwards launches to the existing process and restores its window
from the tray. macOS declares the scheme in its app bundle. On Windows and Linux,
run the packaged app once to register the scheme for the current user. The Linux
AppImage registers its persistent `APPIMAGE` path, so run it again after moving
the file. Development JVM launches do not register a URL handler.

For a manual Android check:

```sh
adb shell am start -W -a android.intent.action.VIEW -d 'olcbox://add?url=https%3A%2F%2Fexample.org%2Fsub' org.olcbox.app
```

Verify that the dialog opens with `https://example.org/sub`, without downloading
it. Dismiss it, open the same link again with the app running, and verify it opens
again. An unsupported action or malformed percent encoding displays an error.
