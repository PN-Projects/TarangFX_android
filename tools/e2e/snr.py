import sys, wave, struct, math
def load(p):
    w = wave.open(p); sr = w.getframerate(); ch = w.getnchannels(); n = w.getnframes()
    v = struct.unpack('<%dh' % (n * ch), w.readframes(n))
    return sr, [v[i * ch] / 32768 for i in range(n)]
def db(xs): return 20 * math.log10(max(1e-9, math.sqrt(sum(x * x for x in xs) / max(1, len(xs)))))
for p in sys.argv[1:]:
    sr, x = load(p)
    noise = x[int(0.1 * sr):int(0.9 * sr)]           # noise-only lead-in
    speech = x[int(1.5 * sr):len(x) - int(1.5 * sr)]  # speech region
    print('%-28s len %.2fs  noise floor %6.1f dBFS  speech %6.1f dBFS  -> speech/noise %5.1f dB'
          % (p.split('/')[-1], len(x) / sr, db(noise), db(speech), db(speech) - db(noise)))
