# Erzeugt eine schoen gestaltete Beam!-Anwender-Anleitung als echte .docx (OOXML, gezippt).
# Keine externen Abhaengigkeiten. Skript ist rein ASCII (PowerShell 5.1 / ANSI-sicher);
# Sonderzeichen werden zur Laufzeit als [char] eingesetzt.

$ErrorActionPreference = "Stop"
$outFile = "C:\Users\KEIBEL-OFFICE\androidstudioprojects\beam\Beam_User_Guide.docx"

$mu   = [char]0xB5      # micro sign (uTP)
$dash = [char]0x2013    # en dash

function Esc([string]$s) {
    return $s.Replace("&","&amp;").Replace("<","&lt;").Replace(">","&gt;")
}

# Wandelt Text mit **bold**-Markern in eine Folge von <w:r>-Runs um.
function Runs([string]$text) {
    $parts = $text -split '(\*\*)'
    $bold = $false
    $sb = New-Object System.Text.StringBuilder
    foreach ($p in $parts) {
        if ($p -eq '**') { $bold = -not $bold; continue }
        if ($p -eq '') { continue }
        $rpr = if ($bold) { '<w:rPr><w:b/></w:rPr>' } else { '' }
        [void]$sb.Append("<w:r>$rpr<w:t xml:space=""preserve"">$(Esc $p)</w:t></w:r>")
    }
    return $sb.ToString()
}

function Para([string]$style, [string]$text) {
    return "<w:p><w:pPr><w:pStyle w:val=""$style""/></w:pPr>$(Runs $text)</w:p>"
}

function Bullet([string]$text) {
    $pPr = '<w:pPr><w:pStyle w:val="ListBullet"/><w:ind w:left="360" w:hanging="360"/></w:pPr>'
    return "<w:p>$pPr<w:r><w:t>&#8226;</w:t></w:r><w:r><w:tab/></w:r>$(Runs $text)</w:p>"
}

# ---------- Inhalt (Englisch) ----------
$body = New-Object System.Collections.ArrayList
function Add-X($x) { [void]$body.Add($x) }

Add-X (Para "Title"    "Beam!")
Add-X (Para "Subtitle" "Share anything, in full quality $dash phone to phone, phone to PC. No cloud, no accounts, no limits.")

Add-X (Para "Heading1" "What is Beam!?")
Add-X (Para "Normal"   "Beam sends your files directly from one device to another over the BitTorrent network. Your photos and videos arrive in their **original quality**, because they never pass through a messenger that recompresses them. You share a small **.beam** link (for example via WhatsApp), and the file travels device-to-device.")
Add-X (Para "Normal"   "**One link, many receivers:** as long as the sender stays online, the same .beam link can be downloaded by as many people $dash and devices $dash as you like.")

Add-X (Para "Heading1" "Sending a file")
Add-X (Bullet "In any app, tap **Share** and choose **Beam!**.")
Add-X (Bullet "Optional: for large videos you can reduce the quality/size first.")
Add-X (Bullet "Optional: turn on the **lock** to encrypt with a passphrase (see Encryption).")
Add-X (Bullet "Beam creates a small **.beam** file $dash send it to your friend with any app (WhatsApp, email, ...).")
Add-X (Bullet "Keep the sending device **awake and online** until the receiver is done $dash it serves the data.")

Add-X (Para "Heading1" "Receiving a file")
Add-X (Bullet "Tap the received **.beam** file $dash Beam opens and the download starts automatically.")
Add-X (Bullet "If your file manager does not offer Beam, open Beam and use the **Import** button (bottom-left) to pick the .beam file.")
Add-X (Bullet "You can also paste a copied link with **Paste link**.")
Add-X (Bullet "Photos and videos go to your **gallery**; documents go to **Download/Beam**.")

Add-X (Para "Heading1" "The activity-card buttons")
Add-X (Bullet "**Copy link** $dash copy the link to use in other torrent apps.")
Add-X (Bullet "**Reshare** $dash invite more people to download the same file.")
Add-X (Bullet "**Restart** $dash fully restart a stuck transfer (e.g. after a network change). Same effect as deleting and re-opening the link, but without losing progress.")
Add-X (Bullet "**Hourglass** $dash nothing to open yet.")
Add-X (Bullet "**Play** $dash play a finished video, or stream a single video while it downloads.")
Add-X (Bullet "**Open** $dash open the gallery (for media) or the Download/Beam folder (for documents).")
Add-X (Bullet "**Delete** $dash remove everything, including the received file(s).")
Add-X (Bullet "**Remove card** $dash keep the files, just clear the card.")

Add-X (Para "Heading1" "Encryption")
Add-X (Para "Normal" "Tap the **lock** in the bar at the top and enter a passphrase before sending. The file is end-to-end encrypted, and the receiver must enter the **same passphrase** to open it. Encrypted videos cannot be streamed during download $dash they are decrypted only once complete.")

Add-X (Para "Heading1" "Sending several files at once (bundles)")
Add-X (Para "Normal" "Select multiple files and share them to **Beam!** $dash they travel as a single **.beam**. The receiver gets them all back as individual files. This works both on the phone and in the PC version.")

Add-X (Para "Heading1" "Faster and more reliable")
Add-X (Bullet "**Best speed:** put both devices on the same Wi-Fi, or use one phone's hotspot and connect the other to it $dash this avoids the router bottleneck.")
Add-X (Bullet "Over **mobile data** a direct connection is not always possible (carrier NAT); same Wi-Fi is the most reliable.")
Add-X (Bullet "Turn **off battery optimization** for Beam (on Xiaomi/MIUI also enable ""Autostart"").")
Add-X (Bullet "On mobile data, disable **""Data Saver""** for Beam, otherwise transfers get throttled.")
Add-X (Bullet "Keep the sending device **awake/charging** until the receiver is finished.")
Add-X (Bullet "If a transfer seems stuck, tap **Restart**.")

Add-X (Para "Heading1" "Settings")
Add-X (Bullet "**Trackers** $dash help sender and receiver find each other. Both should use the same trackers (the defaults work).")
Add-X (Bullet "**Connectivity** $dash after how many seconds Beam may switch to ${mu}TP for better connectivity.")
Add-X (Bullet "**Share Beam!** $dash send the app itself to friends so they can install it.")
Add-X (Bullet "**Clean up temp files** $dash free space by removing leftover temporary files. Your received files are never touched.")

Add-X (Para "Heading1" "Beam! on Windows (PC)")
Add-X (Para "Normal" "Install Beam on Windows with the provided installer (**Beam-x.x.x.msi**) $dash no Java needed.")
Add-X (Bullet "**Send:** select one or more files, right-click, then **Send to -> BEAM!**, and share the created .beam further.")
Add-X (Bullet "**Receive:** open a .beam file (e.g. from WhatsApp on the PC) $dash files download to your **Downloads\Beam** folder, which opens automatically when done.")
Add-X (Bullet "The same .beam link works across **phone and PC**, and for several receivers at once.")

Add-X (Para "Heading1" "Privacy")
Add-X (Para "Normal" "Your file goes straight from device to device $dash no server ever sees its contents. With encryption on, even the file name is hidden, and only someone with your passphrase can open it.")

Add-X (Para "Footer" "(c) 2026 Dr. Andreas Keibel  -  Beam!")

$bodyXml = ($body -join "")

# ---------- Word-Parts (rein ASCII) ----------
$contentTypes = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>
'@

$rels = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
'@

$docRels = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>
'@

$styles = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault><w:rPr><w:rFonts w:ascii="Segoe UI" w:hAnsi="Segoe UI" w:cs="Segoe UI"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:color w:val="222222"/></w:rPr></w:rPrDefault>
    <w:pPrDefault><w:pPr><w:spacing w:after="140" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
  <w:style w:type="paragraph" w:styleId="Title">
    <w:name w:val="Title"/>
    <w:pPr><w:spacing w:before="120" w:after="40"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="Segoe UI" w:hAnsi="Segoe UI"/><w:b/><w:color w:val="1565C0"/><w:sz w:val="64"/><w:szCs w:val="64"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Subtitle">
    <w:name w:val="Subtitle"/>
    <w:pPr><w:spacing w:after="320"/><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="8" w:color="BBDEFB"/></w:pBdr></w:pPr>
    <w:rPr><w:i/><w:color w:val="555555"/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:keepNext/><w:spacing w:before="320" w:after="120"/><w:pBdr><w:bottom w:val="single" w:sz="4" w:space="4" w:color="90CAF9"/></w:pBdr></w:pPr>
    <w:rPr><w:rFonts w:ascii="Segoe UI" w:hAnsi="Segoe UI"/><w:b/><w:color w:val="1976D2"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="ListBullet">
    <w:name w:val="List Bullet"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:spacing w:after="80"/></w:pPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Footer">
    <w:name w:val="FooterLine"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:spacing w:before="360"/><w:jc w:val="center"/><w:pBdr><w:top w:val="single" w:sz="4" w:space="6" w:color="DDDDDD"/></w:pBdr></w:pPr>
    <w:rPr><w:color w:val="888888"/><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr>
  </w:style>
</w:styles>
'@

$document = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
  $bodyXml +
  '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>' +
  '</w:body></w:document>'

# ---------- Zip zur .docx (Eintraege mit Forward-Slash, Office-konform) ----------
$utf8 = New-Object System.Text.UTF8Encoding($false)
if (Test-Path $outFile) { Remove-Item $outFile -Force }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$fs = [System.IO.File]::Open($outFile, [System.IO.FileMode]::Create)
$zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
function Add-Entry($zip, $name, $content, $enc) {
    $entry = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
    $es = $entry.Open()
    $bytes = $enc.GetBytes($content)
    $es.Write($bytes, 0, $bytes.Length)
    $es.Dispose()
}
Add-Entry $zip "[Content_Types].xml"          $contentTypes $utf8
Add-Entry $zip "_rels/.rels"                   $rels         $utf8
Add-Entry $zip "word/document.xml"             $document     $utf8
Add-Entry $zip "word/styles.xml"               $styles       $utf8
Add-Entry $zip "word/_rels/document.xml.rels"  $docRels      $utf8
$zip.Dispose()
$fs.Dispose()

"OK: $outFile ($([math]::Round((Get-Item $outFile).Length/1KB,1)) KB)"
