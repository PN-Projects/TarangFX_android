import sys, wave, struct, random, math
src, dst = sys.argv[1], sys.argv[2]
w = wave.open(src); sr = w.getframerate(); n = w.getnframes()
x = struct.unpack('<%dh' % n, w.readframes(n))
random.seed(7)
pad = sr  # 1 s of noise-only lead-in and tail to measure the noise floor
out = []
hum = lambda i: 0.02 * math.sin(2 * math.pi * 50 * i / sr)
for i in range(n + 2 * pad):
    s = x[i - pad] / 32768 if pad <= i < n + pad else 0.0
    s = s * 0.7 + 0.05 * (random.random() * 2 - 1) + hum(i)
    out.append(int(max(-1, min(1, s)) * 32767))
o = wave.open(dst, 'wb'); o.setnchannels(1); o.setsampwidth(2); o.setframerate(sr)
o.writeframes(struct.pack('<%dh' % len(out), *out)); o.close()
print('written', dst, len(out) / sr, 's')
