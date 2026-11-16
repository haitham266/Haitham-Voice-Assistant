import sys, json, wave, vosk
vosk.SetLogLevel(-1)
model = vosk.Model(sys.argv[1]); wakeg=open(sys.argv[2]).read(); cmdg=open(sys.argv[3]).read()
WT=["يا","مساعد"]
def words(js): return [(w['word'],w['conf'],w['start'],w['end']) for w in json.loads(js).get('result',[])]
for f in sys.argv[4:]:
    data=wave.open(f).readframes(10**9)
    r=vosk.KaldiRecognizer(model,16000,wakeg); r.SetWords(True)
    ws=[]
    for i in range(0,len(data),3200):
        if r.AcceptWaveform(data[i:i+3200]): ws+=words(r.Result())
    ws+=words(r.FinalResult())
    hit=None
    for i in range(len(ws)-1):
        if ws[i][0]==WT[0] and ws[i+1][0]==WT[1] and (ws[i][1]+ws[i+1][1])/2>=0.5: hit=ws[i+1]; break
    trailing = hit is not None and any(w[2]>=hit[3]-0.01 and w[3]>hit[3]+0.05 and w[1]>0.3 for w in ws)
    cmd=""; conf=0
    if hit:
        c=vosk.KaldiRecognizer(model,16000,cmdg); c.SetWords(True); cw=[]
        for i in range(0,len(data),3200):
            if c.AcceptWaveform(data[i:i+3200]): cw+=words(c.Result())
        cw+=words(c.FinalResult()); cmd=" ".join(w[0] for w in cw); conf=sum(w[1] for w in cw)/max(1,len(cw))
    print(f"{f.split('/')[-1]}\t{'WAKE' if hit else 'no-wake'}\t{'trailing' if trailing else ''}\t{cmd}\t{conf:.2f}")
