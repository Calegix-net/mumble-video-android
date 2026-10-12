#!/usr/bin/env python3
"""A headless Mumble peer for testing the Android client against a real server.

It never opens an audio device. Voice goes over the TCP tunnel (no UDP crypto needed), encoded and
decoded with the system libopus through ctypes. Received voice is reduced to per-talk-spurt
statistics (packets, duration, peak level) and thrown away; nothing is recorded.

    tools/testpeer.py HOST [--port 64738] [--name bot] [--say TEXT] [--echo]
                           [--make-channel NAME] [--tone SECONDS] [--tone-dbfs -40] [--stay SECONDS]

--echo replies to every text message it receives. --tone sends a sine at --tone-dbfs (keep it
quiet: the phone plays it through its speaker). Prints one line per event, prefixed with the time.
"""

import argparse
import ctypes
import ctypes.util
import math
import socket
import ssl
import struct
import threading
import time

# ---- protobuf, by hand: only the handful of fields we need ---------------------------------------


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def field_varint(num, value):
    return varint(num << 3) + varint(value)


def field_bytes(num, data):
    if isinstance(data, str):
        data = data.encode()
    return varint((num << 3) | 2) + varint(len(data)) + data


def parse(buf):
    """Returns {field: [values]}; varints as int, length-delimited as bytes. Skips fixed32/64."""
    fields, i = {}, 0
    while i < len(buf):
        key, i = read_varint(buf, i)
        num, wire = key >> 3, key & 7
        if wire == 0:
            val, i = read_varint(buf, i)
        elif wire == 2:
            ln, i = read_varint(buf, i)
            val, i = buf[i:i + ln], i + ln
        elif wire == 1:
            val, i = buf[i:i + 8], i + 8
        elif wire == 5:
            val, i = buf[i:i + 4], i + 4
        else:
            raise ValueError("wire type %d" % wire)
        fields.setdefault(num, []).append(val)
    return fields


def read_varint(buf, i):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


VERSION, UDPTUNNEL, AUTHENTICATE, PING, REJECT, SERVERSYNC = 0, 1, 2, 3, 4, 5
CHANNELSTATE, USERREMOVE, USERSTATE, TEXTMESSAGE, PERMISSIONDENIED = 7, 8, 9, 11, 12

# ---- opus ----------------------------------------------------------------------------------------

lib = ctypes.CDLL(ctypes.util.find_library("opus") or "libopus.so.0")
lib.opus_encoder_create.restype = ctypes.c_void_p
lib.opus_decoder_create.restype = ctypes.c_void_p
lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_char_p, ctypes.c_int32]
lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32, ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]


class Peer:
    def __init__(self, args):
        self.args = args
        self.names = {}
        self.session = None
        self.lock = threading.Lock()
        self.spurts = {}
        self.decoders = {}
        self.synced = threading.Event()
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        raw = socket.create_connection((args.host, args.port), timeout=10)
        self.sock = ctx.wrap_socket(raw)
        self.sock.settimeout(None)

    def log(self, text):
        print("%s %s" % (time.strftime("%H:%M:%S"), text), flush=True)

    def send(self, kind, payload):
        with self.lock:
            self.sock.sendall(struct.pack(">HI", kind, len(payload)) + payload)

    def run(self):
        self.send(VERSION, field_varint(1, (1 << 16) | (7 << 8)) + field_varint(5, (1 << 48) | (7 << 32))
                  + field_bytes(2, "testpeer") + field_bytes(3, "Linux"))
        self.send(AUTHENTICATE, field_bytes(1, self.args.name) + field_varint(5, 1))
        threading.Thread(target=self.reader, daemon=True).start()
        threading.Thread(target=self.pinger, daemon=True).start()
        if not self.synced.wait(15):
            self.log("never synchronised")
            return
        self.log("synchronised as session %d; users: %s" % (self.session, ", ".join(sorted(self.names.values()))))
        if self.args.make_channel:
            # A temporary channel under root; the server moves its creator into it.
            self.send(CHANNELSTATE, field_varint(2, 0) + field_bytes(3, self.args.make_channel) + field_varint(8, 1))
            self.log("created channel %s" % self.args.make_channel)
        if self.args.say:
            self.send(TEXTMESSAGE, field_varint(3, 0) + field_bytes(5, self.args.say))
            self.log("said: %s" % self.args.say)
        if self.args.tone:
            self.send_tone(self.args.tone, self.args.tone_dbfs)
        time.sleep(self.args.stay)
        self.flush_spurts(force=True)

    def pinger(self):
        while True:
            self.send(PING, field_varint(1, int(time.time() * 1e6)))
            time.sleep(10)
            self.flush_spurts(force=True)  # long talk spurts report every 10 s

    def reader(self):
        buf = b""
        while True:
            data = self.sock.recv(65536)
            if not data:
                self.log("server closed the connection")
                return
            buf += data
            while len(buf) >= 6:
                kind, ln = struct.unpack(">HI", buf[:6])
                if len(buf) < 6 + ln:
                    break
                self.handle(kind, buf[6:6 + ln])
                buf = buf[6 + ln:]

    def handle(self, kind, payload):
        if kind not in (SERVERSYNC, REJECT, USERSTATE, USERREMOVE, TEXTMESSAGE, UDPTUNNEL, PERMISSIONDENIED):
            return
        if kind == UDPTUNNEL:
            self.audio(payload)
            return
        try:
            f = parse(payload)
        except (ValueError, IndexError):
            return
        if kind == PERMISSIONDENIED:
            self.log("permission denied: %s" % f.get(4, [b""])[0].decode(errors="replace"))
        elif kind == SERVERSYNC:
            self.session = f[1][0]
            self.synced.set()
        elif kind == REJECT:
            self.log("rejected: %s" % f.get(2, [b""])[0].decode(errors="replace"))
        elif kind == USERSTATE:
            if 3 in f:
                session = f[1][0]
                new = session not in self.names
                self.names[session] = f[3][0].decode(errors="replace")
                if new and self.synced.is_set():
                    self.log("joined: %s" % self.names[session])
            if self.synced.is_set() and 1 in f and (9 in f or 10 in f):
                who = self.names.get(f[1][0], "?")
                self.log("%s: self_mute=%s self_deaf=%s" % (who, bool(f.get(9, [0])[0]), bool(f.get(10, [0])[0])))
            if self.synced.is_set() and 5 in f and 1 in f:
                self.log("%s moved to channel %d" % (self.names.get(f[1][0], "?"), f[5][0]))
        elif kind == USERREMOVE:
            self.log("left: %s" % self.names.pop(f[1][0], "?"))
        elif kind == TEXTMESSAGE:
            who = self.names.get(f.get(1, [0])[0], "server")
            text = f.get(5, [b""])[0].decode(errors="replace")
            shown = text if len(text) < 200 else "%s... (%d chars)" % (text[:80], len(text))
            self.log("text from %s: %s" % (who, shown))
            if self.args.echo and who != self.args.name:
                reply = "echo: %d chars received" % len(text) if "<img" in text else "echo: " + text
                self.send(TEXTMESSAGE, field_varint(3, 0) + field_bytes(5, reply))
        elif kind == UDPTUNNEL:
            self.audio(payload)  # the payload is the raw UDP plaintext, not a protobuf message

    def audio(self, packet):
        if not packet or packet[0] != 0:
            return
        f = parse(packet[1:])
        sender = f.get(3, [0])[0]
        opus = f.get(5, [b""])[0]
        terminator = bool(f.get(16, [0])[0])
        dec = self.decoders.get(sender)
        if dec is None:
            err = ctypes.c_int()
            dec = self.decoders[sender] = lib.opus_decoder_create(48000, 1, ctypes.byref(err))
        pcm = (ctypes.c_int16 * 5760)()
        n = lib.opus_decode(dec, opus, len(opus), pcm, 5760, 0)
        peak = max((abs(pcm[i]) for i in range(max(n, 0))), default=0)
        s = self.spurts.setdefault(sender, {"packets": 0, "samples": 0, "peak": 0, "start": time.time(), "last": 0})
        s["packets"] += 1
        s["samples"] += max(n, 0)
        s["peak"] = max(s["peak"], peak)
        s["last"] = time.time()
        if terminator:
            self.report(sender)

    def flush_spurts(self, force=False):
        for sender in list(self.spurts):
            if force or time.time() - self.spurts[sender]["last"] > 1:
                self.report(sender)

    def report(self, sender):
        s = self.spurts.pop(sender, None)
        if not s:
            return
        db = 20 * math.log10(s["peak"] / 32768) if s["peak"] else float("-inf")
        self.log("voice from %s: %d packets, %.2f s of audio, peak %.1f dBFS"
                 % (self.names.get(sender, sender), s["packets"], s["samples"] / 48000, db))

    def send_tone(self, seconds, dbfs):
        err = ctypes.c_int()
        enc = lib.opus_encoder_create(48000, 1, 2048, ctypes.byref(err))
        amp = 32767 * 10 ** (dbfs / 20)
        frame, out, t0 = 960, ctypes.create_string_buffer(4000), time.time()
        packets = int(seconds * 50)
        for k in range(packets):
            pcm = (ctypes.c_int16 * frame)(*[int(amp * math.sin(2 * math.pi * 440 * (k * frame + i) / 48000)) for i in range(frame)])
            n = lib.opus_encode(enc, pcm, frame, out, 4000)
            audio = field_varint(1, 0) + field_varint(4, k * 2) + field_bytes(5, out.raw[:n])
            if k == packets - 1:
                audio += field_varint(16, 1)
            self.send(UDPTUNNEL, b"\x00" + audio)
            time.sleep(max(0, t0 + (k + 1) * 0.02 - time.time()))
        self.log("sent %.1f s tone at %.0f dBFS" % (seconds, dbfs))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("host")
    p.add_argument("--port", type=int, default=64738)
    p.add_argument("--name", default="testpeer")
    p.add_argument("--say")
    p.add_argument("--make-channel")
    p.add_argument("--echo", action="store_true")
    p.add_argument("--tone", type=float, default=0)
    p.add_argument("--tone-dbfs", type=float, default=-40)
    p.add_argument("--stay", type=float, default=30)
    Peer(p.parse_args()).run()


if __name__ == "__main__":
    main()
