import sys, wave, struct, math
w = wave.open(sys.argv[1]); sr = w.getframerate(); ch = w.getnchannels(); n = w.getnframes()
x = struct.unpack('<%dh' % (n*ch), w.readframes(n))[0::ch]
seg = x[int(1.0*sr):int(1.0*sr)+8192]
def g(f):
    k = 2*math.cos(2*math.pi*f/sr); s1 = s2 = 0.0
    for v in seg: s0 = v + k*s1 - s2; s2 = s1; s1 = s0
    return 10*math.log10(max(1e-9, s1*s1 + s2*s2 - k*s1*s2))
print(" ".join("%.1f" % g(f) for f in (60, 261.63, 523.25, 329.63, 659.25)))
