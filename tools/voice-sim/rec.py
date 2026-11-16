import sys, json, wave, vosk
vosk.SetLogLevel(-1)
model = vosk.Model(sys.argv[1]); grammar = sys.argv[2] if len(sys.argv)>2 and sys.argv[2] != '-' else None
for f in sys.argv[3:]:
    wf = wave.open(f)
    r = vosk.KaldiRecognizer(model, 16000, grammar) if grammar else vosk.KaldiRecognizer(model, 16000)
    out=[]
    while True:
        d = wf.readframes(4000)
        if not d: break
        if r.AcceptWaveform(d): out.append(json.loads(r.Result())['text'])
    out.append(json.loads(r.FinalResult())['text'])
    print(f.split('/')[-1], '=>', ' | '.join(x for x in out if x))
