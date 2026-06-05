---
name: gcm-oom-chunked-crypto
description: Verschlüsselung großer Dateien crashte (OOM) — Android-GCM puffert alles; Fix = gechunktes GCM
metadata: 
  node_type: memory
  type: project
  originSessionId: 944356e9-b04c-4da2-bf38-6fd02b79c83d
---

**Bug (2026-06-02):** Verschlüsseln einer 606-MB-Datei stürzte reproduzierbar bei ~22 % ab.
Ursache: Androids `Cipher` mit **AES/GCM puffert den GANZEN Datenstrom im RAM** (AEAD-Tag über
alles) — `CipherOutputStream` streamt bei GCM NICHT wirklich. Bei ~133 MB (=22 % von 606) ist der
Heap voll → OutOfMemory-Crash. Galt für jede Datei > ~100–150 MB.

**Fix (`media/FileCrypto.kt`, Format BEAMENC2):** Datei in **1-MB-Klartext-Chunks** zerlegen, jeden
Chunk als eigenes AES-256-GCM (Nonce = `noncePrefix(8) || chunkIndex(4)`, eindeutig pro Key).
Container: `MAGIC2(8)|salt(16)|noncePrefix(8)| [ ctLen(4)|ciphertext ]…`. Speicher bleibt bei ~1 MB,
Wrong-Passphrase-Erkennung (AEADBadTagException im 1. Chunk) bleibt erhalten. `decrypt()` liest
weiterhin auch das alte Einzel-GCM-Format `BEAMENC1` (für noch kursierende Alt-Links; das alte
DEcrypt puffert ebenfalls → nur für kleine Dateien tauglich, aber Schreiben ist immer BEAMENC2).

**Lehre:** Auf Android NIE einen einzelnen GCM-Cipher über große Datenmengen laufen lassen —
immer chunken. Gilt analog für jede künftige Krypto über große Streams.
