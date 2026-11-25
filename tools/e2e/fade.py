import sys, wave, struct, math
w = wave.open(sys.argv[1]); sr = w.getframerate(); n = w.getnframes(); ch = w.getnchannels()
x = struct.unpack('<%dh' % (n*ch), w.readframes(n))
def rms(a, b): seg = x[int(a*sr)*ch:int(b*sr)*ch]; return 20*math.log10(max(1e-9, math.sqrt(sum(v*v for v in seg)/len(seg))/32768))
print("%.1f %.1f %.4f" % (rms(0, 0.25), rms(float(sys.argv[2]) + 0.5, float(sys.argv[2]) + 1.5), max(abs(v) for v in x)/32768))
