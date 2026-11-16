import sys, json, wave, vosk
vosk.SetLogLevel(-1)
model = vosk.Model(sys.argv[1]); grammar = sys.argv[2]
for f in sys.argv[3:]:
    wf = wave.open(f)
    r = vosk.KaldiRecognizer(model, 16000, grammar); r.SetWords(True)
    res=[]
    while True:
        d = wf.readframes(4000)
        if not d: break
        if r.AcceptWaveform(d): res.append(json.loads(r.Result()))
    res.append(json.loads(r.FinalResult()))
    words=[(w['word'],round(w['conf'],2)) for x in res for w in x.get('result',[])]
    print(f.split('/')[-1], words)
