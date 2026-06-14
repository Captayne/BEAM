# EN | DE

# Beam! What is it?

**Original files. Full quality. No cloud. No account.**  
Beam shares whole collections of high-resolution files — up to gigabytes — directly between PC and phone, without paid middlemen and without losing the metadata inside the files (location, camera settings, file name, date). Ideal for journalists, or simply for sending holiday videos and photos as they really are.

## Sending files

Press **Choose file(s)** and pick what to share — or in Windows Explorer use **Send to → Beam!**, or simply **drag the files onto the Beam window**.

For videos you can pick a lower **Quality** first: **4K · FHD · 720p · 360p · 180p**. Beam only ever shrinks — a level is applied only if it is smaller than the source video; otherwise the original is sent untouched (never upscaled). Frame rate stays original.

Beam creates a tiny **.beam** file. Share it via **Share (copy .beam)** (then paste it into WhatsApp / e-mail), or **Show in Explorer** to grab the file yourself.

**Important:** Your sending PC is the server. It must stay awake and online until the receiver(s) are done — so disable sleep. If a direct connection isn't possible, the **📡 Relay** steps in (see below).

## Chrono: a whole event in one go

Hundreds of photos from a trip, party or conference? Don't pick them one by one.

Select one file from the **beginning** and one from the **end** of the event, then tick **Send Chronology**. Beam takes the oldest and newest selected file as the time span and adds everything in between (same folder).

The send preview updates live with file count, total size and the exact time span — so you can check before you press **BEAM!**. Chrono sends originals unless you choose a lower video quality.

## Receiving files

Press **Open .beam** and pick the received file — or just **double-click** a saved **.beam**. The transfer starts automatically; the receiver doesn't need to do anything else, not even for the relay.

As files arrive, Beam opens the target folder and highlights every fully downloaded file, so you can watch them come in — with large-icon view you even get live thumbnails — and sort them straight away. Files are saved to **Downloads\Beam**; **Show in Explorer** opens the folder.

## Optional encryption

When sending, enable encryption and set a passphrase for end-to-end encryption. The receiver needs the same passphrase — even the relay only ever sees ciphertext.

## Buttons on a transfer card

| Button | Meaning |
| --- | --- |
| **Share (copy .beam)** | Copies the .beam to the clipboard; paste it into WhatsApp / e-mail as an attachment. |
| **Show in Explorer** | Reveals the .beam file (sender) or the received files (receiver). |
| **Reconnect** | Retry, e.g. after the network has changed. |
| **📡 Relay NOW!** | Sender only. If no direct transfer is possible (e.g. both sides behind carrier-grade NAT), the data flows anonymously in chunks through the Beam relay. The receiver handles the relay automatically. |
| **Remove** | Removes the card; the received files are kept. |

## Trackers and DHT

Trackers help sender and receiver find each other through the shared hash. The default list is fine; both sides should use the same trackers. `http://217.160.159.14/bs7Kf3R9xLmQ2v/announce` is Beam's own tracker for BEAM! users, so it should always work.

**Allow DHT** additionally uses the public Distributed Hash Table. It can help discovery, but public DHT crawlers may see the hash and try to connect. For quieter transfers, turn DHT off (in the phone app's Settings). For sensitive files: encryption beats hope.

## Faster and more reliable

- Best speed: a direct connection on the same network (LAN / Wi-Fi). The relay is only a fallback.
- Over carrier-grade NAT (common on mobile and some company networks) a direct connection isn't always possible; then the relay steps in.
- Keep the sending PC awake (disable sleep) until the receiver is done.

## Company network blocking it?

Some company networks block peer-to-peer traffic completely, especially with security layers such as Zscaler. Beam cannot and should not get around corporate rules — and we don't want to get anyone into trouble.

Tip: switch to a phone hotspot, or work from home. Even on a company VPN, normal web traffic often goes out directly via split tunneling — and so does Beam!.

## WhatsApp won't open a .beam?

After installing or updating Beam, WhatsApp Desktop may say "opening…" while nothing happens — Windows invalidates the file link's security hash on every (re)install. Fix: click **🔧 Repair** (top bar) → in Settings set **.beam → Beam**, then **sign out of Windows and back in once**. In the meantime a double-click on a saved .beam always works.

The window is a compact box you can resize freely; it remembers nothing you don't want it to — no cloud, no account.

---

© 2026 Dr. Andreas Keibel
