# EN | DE

# Beam! What is it?

**Original files. Full quality. No cloud. No account.**  
Beam is made for sharing whole collections of high-resolution files — up to gigabytes — without paid middlemen and without losing metadata inside the files, such as location, camera settings, file name and date. Ideal for journalists, or simply for sending holiday videos and photos as they really are.

## Sending files

Share files from your gallery, file manager or any app to **Beam!** using the normal share menu.

For videos, you can reduce quality to: **4K · FHD · 720p · 360p · 180p**. Beam never upscales; the frame rate stays original.

Beam creates a tiny **.beam** file. Send/share that file to the receiver via WhatsApp, email, chat — whatever works.

On PC: use **Send to → Beam!**, or open Beam and choose the files manually.

**Important:** The sending device is the direct sender. The files are not buffered by some third-party service. _That is exactly BEAM!_ The sending device must stay awake and online until the download is finished. If the network gets grumpy, use the **📡 Relay** button.

## Chrono: a whole event in one go

600 photos from a trip, party or conference? Please don’t tap them one by one.

Choose one file from the **beginning** and one from the **end** of the event, share them to Beam and activate **Chrono**. Beam uses the oldest and newest selected file as the time span and adds everything in between: photos, videos, screenshots, WhatsApp images.

The send card updates live with file count, total size and the exact time span. So you can check whether the selection is right before you hit **BEAM!**.

Chrono sends originals unless you choose a smaller video quality.  
For long transfers, keep an eye on the battery.

## Receiving files

Open the received **.beam** file with Beam. On PC, clicking the Beam message in common clients such as WhatsApp, Signal or Threema should start the app. As files arrive, PC Beam opens the target folder and highlights all fully downloaded files, so you can watch them come in and sort them right away.

Photos and videos go to the gallery; everything else goes to **.../Download/Beam**.

## Optional encryption

When sending, you can enable encryption and set a passphrase. The receiver needs the same passphrase.

Use it for sensitive files, so all outsiders ever see is delicious character soup.

## Icons on the activity card

| Icon | Meaning                                                                                                                                                                                  |
| ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 📋   | Copy link, e.g. for other torrent apps.                                                                                                                                                  |
| 🔄   | Reconnect / retry, for example after a network change.                                                                                                                                   |
| ↗    | Share the running transfer again and invite more receivers.                                                                                                                              |
| 📡   | Relay. Use it on the sender side if a direct connection is blocked by mobile networks, carrier NAT or stubborn routers. It deliberately does **not** bypass corporate security measures. |
| ⏳    | No file is ready yet.                                                                                                                                                                    |
| ▶/📂 | ▶: stream unencrypted videos during download. <br/>📂: Open file or folder, when completed.                                                                  |
| 🗑   | DELETE EVERYTHING: removes received files **and** the activity card. Gone means gone.                                                                                                    |
| ✕    | Removes only the activity card. The files stay where they are.                                                                                                                           |

## Trackers and DHT

Trackers help sender and receiver find each other through the shared hash. The default list is fine; sender and receiver should use the same trackers. `http://217.160.159.14/bs7Kf3R9xLmQ2v/announce` is Beam’s own tracker, which exclusively listens to BEAM! users, so this should always work.

**Allow DHT** additionally uses the public Distributed Hash Table. This can help discovery, but public DHT crawlers may see the hash and try to connect. For quieter transfers, turn DHT off. Beam’s own station, peer hints and relay continue to work.

For sensitive files: encryption beats hope.

## Go for Performance:

Best case: both devices are on the same **5 GHz Wi-Fi**, or one phone creates a hotspot and the other connects to it.

Mobile data is less predictable because many providers use carrier NAT. If a direct connection does not work: use Relay.

On Android, let Beam run freely and performantly:

- turn off battery optimization for Beam;
- on Xiaomi/MIUI and similar systems, enable **Autostart**;
- turn off **Data Saver**, or allow unrestricted data;
- keep the sending device awake and plugged in;
- when no transfer is running, Beam calms itself down and saves energy.

## Company network blocking it?

Some company networks block peer-to-peer traffic completely, especially when security systems such as Zscaler are involved. Beam cannot and should not trick company rules. Let’s leave it that way. Background: corporate security alerts may fire — and we do not want to get anyone into trouble.

Use a phone hotspot or a normal home network. Even with a company VPN, normal web traffic often goes out directly via split tunneling — and Beam! can beam.

## Keep compressed video and data

If you compress videos, the compressed copies can stay on the device after the transfer. This option also saves data on the phone that did not originally come from the phone itself, for example from a connected SD card.

Use case: beam recordings from a connected SD card — drone, GoPro, etc. — to the PC by selecting them, sharing with Beam and forwarding them, while also keeping a copy on the phone.

---

© 2026 Dr. Andreas Keibel
