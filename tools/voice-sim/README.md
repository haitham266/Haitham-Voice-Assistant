# أدوات محاكاة الصوت (على الحاسوب)

تُستخدم لاختبار النموذج العربي نفسه خارج الهاتف (Python + vosk):

```bash
pip install vosk          # أو: pip install --no-deps vosk + srt stub
espeak-ng -v mb-ar2 -w a.wav "يا مساعد اتصل بأحمد" && sox a.wav -r 16000 -c 1 -b 16 t.wav
python3 sim.py <model_dir> wake.json cmd.json t.wav
```

- `wake.json` و `cmd.json`: القواعد التي يبنيها التطبيق فعلياً (مولدة بـ `PipelineFixtureTest` من قاموس النموذج).
- `sim-results.tsv`: نتائج التشغيل المذكورة في `docs/TEST_REPORT.md`.
