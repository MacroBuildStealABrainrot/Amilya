"""Send only generated PCM to the local emulator. No host microphone is used."""
import array
import math
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
                                        channels=messages.AudioFormat.Mono, mode=messages.AudioFormat.MODE_REAL_TIME)
    started = time.monotonic()
    for i in range(3000):
        delay = started + i * period_ms / 1000 - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        yield messages.AudioPacket(format=audio_format, timestamp=time.time_ns() // 1000, audio=blocks[i % len(blocks)])


if __name__ == "__main__":
    with grpc.insecure_channel("localhost:8554") as channel:
        grpc.channel_ready_future(channel).result(timeout=5)
        controller = services.EmulatorControllerStub(channel)
        print("Injecting 440 Hz PCM into the active emulator microphone.")
        controller.injectAudio(tone_packets(), timeout=65)
