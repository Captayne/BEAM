Beam! — and what it is

Beam sends your original files — full quality, no cloud, no account — PC ↔ phone ↔ PC, or compress videos on demand.

Beam is a file-sharing application to share files, from small to enormous, between PC and smartphones, using the torrent network. It is intended not to lose any meta information stored in the files — like the location of photos, the real file name, the device used… just the original file.

📤 How to Send
Press "Choose file(s)" and pick what you want to share — or in Windows Explorer use "Send to → Beam!", or simply drag the files onto the Beam window.
For large videos you can pick a lower "Quality" first: 4K · FHD · 720p · 360p · 180p. It only ever shrinks — a level is applied only if it is smaller than the source video, otherwise the original is sent untouched (never upscaled). Frame rate stays original.
Beam creates a small .beam file which contains the unique HASH code for identification. Share it with a friend with "Share (copy .beam)" (then paste it into WhatsApp / e-mail), or "Show in Explorer" to grab the file yourself.

Your sending PC is the Server! It needs to stay awake and online so the receiver(s) can download the data from it — so don't let it go to sleep until they are done. If a direct connection isn't possible, a relay (📡) steps in, see below.

🗓 Send Chronology — back up a whole event in one gesture!
The easy way to send the media of an entire event, trip, party or day — e.g. a folder of photos to a friend or to your NAS.
Instead of picking hundreds of files, just select one file from the START and one from the END of the event (e.g. the first and the last photo), then tick "Send Chronology".
• Beam takes the oldest and newest of your selected files as the time span and automatically adds EVERYTHING in between (same folder).
• The preview updates live: file count, total size and the exact time span — so you see exactly what will be sent before you press BEAM!.
Chronology uses the files' modification date and sends your ORIGINALS, unless you choose a lower "Quality" (then only the videos are compressed, photos stay 1:1).

📥 Receive
Press "Open .beam" and pick the received xyz###.beam file — or just double-click a saved .beam.
Beam shows a download activity-card and the transfer starts automatically. Files are saved to your Downloads\Beam folder; "Show in Explorer" opens it.
The receiver doesn't need to do anything else — not even for the relay.

🔒 Encrypt (optional)
When sending, enable encryption and enter a passphrase for end-to-end encryption. The receiver needs the same passphrase to open the files — even the relay only ever sees ciphertext.

🎛 Buttons on a transfer-card:
"Share (copy .beam)"  — copies the .beam to the clipboard; paste it into WhatsApp / e-mail as an attachment.
"Show in Explorer"    — reveals the .beam file (sender) / the received files (receiver).
"Reconnect"           — retry, e.g. after the network has changed.
"📡 Relay NOW!"        — shown on the SENDER's card only. If no direct transfer is possible (e.g. both sides behind carrier-grade NAT), click it; the data then flows anonymously in chunks through the "Beam relay post-office". The receiver handles the relay automatically.
"Remove"              — removes the card; the received files are kept.

🔗 Trackers (advanced)
Trackers help sender and receiver find each other. Open "🛰️ Trackers (advanced)" to edit the list; sender and receiver must use the same trackers (the defaults work). Beam's own station is on top. Trackers let both sides find each other anonymously by their shared HASH — it can take a little while until two sides looking for the same HASH meet. 😁

🌐 DHT & privacy
Beam also finds peers via the DHT (a public, decentralized network keyed by the shared HASH). That convenience means crawlers watching the DHT can spot a HASH and connect uninvited (they may show up on a seeding card as TCP(#)/µTP(#)). They only ever get ciphertext if you encrypt — so encrypt anything sensitive. A quieter, "DHT-off" mode is available in the phone app's Settings.

🚀 Faster & more reliable
• Best speed: a direct connection on the same network (LAN / Wi-Fi) is fastest — the relay is only a fallback.
• Over carrier-grade NAT (common on mobile, and on some company networks) a direct connection isn't always possible; then the relay steps in.
• Keep the sending PC awake (disable sleep) until the receiver is done.
• Company networks with strict firewalls / deep packet inspection can block the relay — a home or mobile network is more reliable.

🏢 Behind a company network?
If a transfer won't connect even with the relay engaged, you may be behind more than a simple firewall. Many companies run professional security layers (e.g. Zscaler — a corporate proxy that inspects all traffic and only lets through company-approved software). Beam's peer-to-peer traffic is blocked there by design; the app cannot get around it.
Tip: switch to a phone hotspot, or work from home. There, even on a company VPN, normal web traffic usually goes out directly past the VPN (split tunneling) — and so does Beam.

The window is a compact box you can resize freely; it remembers nothing you don't want it to — no cloud, no account.

Copyright 2026, faithfully  Dr. Andreas Keibel
