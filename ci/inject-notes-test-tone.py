"""Send only generated PCM to the local emulator. No host microphone is used."""
import array
import math
import os
from pathlib import Path
import time

import grpc
import emulator_controller_pb2 as messages
import emulator_controller_pb2_grpc as services


def tone_packets():
    sample_rate = 48000
    period_ms = 20
    samples = sample_rate * period_ms // 1000
    # Five 20ms blocks contain 44 complete cycles, so this sequence loops without clicks.
    tone = array.array("h", (
        round(18000 * math.sin(2 * math.pi * 440 * i / sample_rate))
        for i in range(5 * samples)
    )).tobytes()
    blocks = [tone[i:i + 2 * samples] for i in range(0, len(tone), 2 * samples)]
    audio_format = messages.AudioFormat(samplingRate=sample_rate, format=messages.AudioFormat.AUD_FMT_S16,
                                        channels=messages.AudioFormat.Mono, mode=messages.AudioFormat.MODE_UNSPECIFIED)
    # Let the guest pull PCM at its capture rate. Only the first packet declares the format.
    for i in range(4500):
        packet = messages.AudioPacket(timestamp=time.time_ns() // 1000, audio=blocks[i % len(blocks)])
        if i == 0:
            packet.format.CopyFrom(audio_format)
        yield packet


def emulator_credentials():
    # The SDK generates this short-lived credential on boot. Nothing from this file is logged.
    runtime = Path(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}"))
    for entry in (runtime / "avd" / "running").glob("pid_*.ini"):
        fields = {}
        for line in entry.read_text().splitlines():
            key, separator, value = line.partition("=")
            if separator:
                fields[key.strip()] = value.strip()
        if fields.get("grpc.port") == "8554" and fields.get("grpc.token"):
            return grpc.composite_channel_credentials(
                grpc.local_channel_credentials(), grpc.access_token_call_credentials(fields["grpc.token"])
            )
    raise RuntimeError("Local emulator authentication was unavailable")


if __name__ == "__main__":
    with grpc.secure_channel("127.0.0.1:8554", emulator_credentials()) as channel:
        grpc.channel_ready_future(channel).result(timeout=5)
        controller = services.EmulatorControllerStub(channel)
        print("Injecting 440 Hz PCM into the active emulator microphone.")
        controller.injectAudio(tone_packets(), timeout=65)
