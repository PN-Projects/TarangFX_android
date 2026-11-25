import math, random, struct, sys, wave
out = sys.argv[1]
sr = 44100
dur = 20.0
chords = [(261.63, 329.63, 392.0), (220.0, 261.63, 329.63), (174.61, 220.0, 261.63), (196.0, 246.94, 293.66)]
random.seed(1)
frames = bytearray()
n = int(sr * dur)
for i in range(n):
    t = i / sr
    ch = chords[int(t / 2.5) % 4]
    env = 0.6 + 0.4 * math.sin(2 * math.pi * 0.4 * t)
    s = sum(math.sin(2 * math.pi * f * t) for f in ch) / 3.0 * 0.5 * env
    s += 0.03 * (random.random() * 2 - 1)  # background hiss
    beat = (t % 0.5) < 0.04
    if beat:
        s += 0.4 * math.sin(2 * math.pi * 60 * t) * (1 - (t % 0.5) / 0.04)
    l = max(-1, min(1, s * 0.9))
    r = max(-1, min(1, s * 0.8))
    frames += struct.pack('<hh', int(l * 32767), int(r * 32767))
with wave.open(out, 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(sr); w.writeframes(bytes(frames))
print("ok", out)
