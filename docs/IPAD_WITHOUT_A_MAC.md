# Getting the receiver onto an iPad without a Mac

An iPad cannot compile an Xcode project, and iPadOS only runs apps that Apple's signing system
has approved for that device. Compiling is easy to move to the cloud. Signing is the real
constraint. The options, most practical first:

## 1. Safari receiver (works today, nothing to install)

The phone app serves the receiver as a web page.

1. Start mirroring on the phone. It shows an address and a 6-digit code.
2. On the iPad, open the address in Safari and type the code.
3. Optional: Share → **Add to Home Screen**. It then opens fullscreen like an app.

Two addresses are shown:

- `http://<phone>:47800` works immediately, using the Media Source decoder.
- `https://<phone>:47801` uses WebCodecs (lower latency). Safari warns that the certificate
  is not trusted, because the phone generated it itself. Choose *Show Details → visit this
  website* once.

Both use the iPad's hardware H.264 decoder. Limits: the address changes if the phone's IP
changes (reserve an address for the phone in your router to avoid that), and Safari cannot
advertise itself, so the connection always starts from the iPad.

## 2. Native app through TestFlight (no computer at all, costs $99/year)

With an Apple Developer Program membership, GitHub's hosted Macs can build, sign and upload
the app, and you install it with the TestFlight app on the iPad. Everything is done from a
browser.

1. Join the Apple Developer Program.
2. In App Store Connect: *Users and Access → Integrations → App Store Connect API*, create a key
   with the **Admin** role. Note the Key ID and Issuer ID and download the `.p8` file.
3. Register a bundle ID of your own (for example `com.yourname.omnimirror`) and create an app
   record for it in App Store Connect.
4. In the GitHub repository: *Settings → Secrets and variables → Actions*, add
   `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` (the file's contents), `APPLE_TEAM_ID`, `IOS_BUNDLE_ID`.
5. Run the **iOS TestFlight** workflow from the Actions tab.
6. Add yourself as an internal tester in App Store Connect and install from TestFlight.

Builds expire after 90 days; run the workflow again to renew.
This workflow is written from Apple's documented procedure but **has not been run yet**,
because it needs an account. Expect to adjust it on first use.

## 3. Native app, free, but needs a computer once

The **iOS** workflow produces `OmniMirror-unsigned.ipa` on every push. Tools such as Sideloadly
or AltStore sign it with a free Apple ID and install it. They need a Windows, macOS or Linux
computer with a cable, at least for the first install, and a free signature lasts 7 days
(AltStore/SideStore can refresh it on the iPad afterwards). A borrowed PC is enough.

## What does not work

- Compiling or signing on the iPad itself.
- Installing an unsigned `.ipa` directly.
- "No computer, no account" signing websites. They use shared enterprise certificates that
  Apple revokes without notice, and they require trusting a stranger's profile on your iPad.

## Recommendation

Use Safari now. If the Media Source/WebCodecs latency is good enough, stop there. Move to
TestFlight if you want Bonjour discovery, phone-initiated connections, and the last few
milliseconds.
