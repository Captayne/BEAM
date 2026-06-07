import socket, time, urllib.request, sys

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
TPORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
PPORT = int(sys.argv[3]) if len(sys.argv) > 3 else 8443
TRACKER = f"http://{HOST}:{TPORT}"
TOKEN = "bs7Kf3R9xLmQ2v"
PIPE = (HOST, PPORT)
INFOHASH = bytes(range(20))  # 00 01 .. 13

def handshake(ih, peer_id):
    return bytes([19]) + b"BitTorrent protocol" + bytes(8) + ih + peer_id

def announce():
    ih_enc = "".join("%%%02x" % b for b in INFOHASH)
    url = f"{TRACKER}/{TOKEN}/announce?info_hash={ih_enc}&peer_id=PYTEST00000000000001&port=6881&compact=1"
    with urllib.request.urlopen(url, timeout=5) as r:
        print("announce ->", r.read())

def main():
    print("== 1) Infohash beim Tracker anmelden (Gate offen machen) ==")
    announce()

    print("== 2) Zwei Sockets durch die Pipe ==")
    a = socket.create_connection(PIPE, timeout=5)
    a.sendall(handshake(INFOHASH, b"AAAAAAAAAAAAAAAAAAAA"))
    time.sleep(0.3)  # A wird 'waiting'
    b = socket.create_connection(PIPE, timeout=5)
    b.sendall(handshake(INFOHASH, b"BBBBBBBBBBBBBBBBBBBB"))

    # Jede Seite soll den Handshake der anderen empfangen (68 Byte)
    a.settimeout(5); b.settimeout(5)
    a_got = a.recv(68)
    b_got = b.recv(68)
    print("A empfing peer_id:", a_got[48:68])   # erwartet BBBB...
    print("B empfing peer_id:", b_got[48:68])   # erwartet AAAA...

    # Nutzdaten durch die Roehre, beide Richtungen
    a.sendall(b"HELLO-FROM-A")
    b.sendall(b"HELLO-FROM-B")
    print("B empfing Daten:", b.recv(64))       # erwartet HELLO-FROM-A
    print("A empfing Daten:", a.recv(64))       # erwartet HELLO-FROM-B

    a.close(); b.close()
    print("== OK ==")

if __name__ == "__main__":
    main()
