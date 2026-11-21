"""Laboratório 2 de voz: candidatas pt-BR (feminina/masculina) + inglês.
Gera amostras de 30 s+, mede 1º áudio (frase curta) e velocidade (RTF) com 2 núcleos, e tamanho do modelo.
Cada candidata roda isolada: se uma falhar, as outras seguem."""
import json, os, sys, time, subprocess, traceback, glob, shutil
import numpy as np, soundfile as sf

OUT = "out"; os.makedirs(OUT, exist_ok=True)
PT = ("Olá! Eu sou a assistente do seu celular e estou aqui para ajudar no seu dia a dia. "
      "Hoje de manhã, conferi sua agenda: você tem uma reunião às dez horas com a equipe de vendas, "
      "e depois do almoço precisa ligar para o banco para resolver aquela questão do cartão. "
      "Também chegaram três e-mails importantes, um deles do seu cliente de São Paulo, pedindo um orçamento até sexta-feira. "
      "Se quiser, eu posso preparar um rascunho da resposta agora mesmo, e você só revisa antes de enviar. "
      "Ah, e não esqueça: amanhã vai chover bastante no fim da tarde, então é melhor sair mais cedo do trabalho. "
      "Quer que eu coloque um lembrete para as quatro e meia?")
EN = ("Hi! I'm the assistant on your phone, and I'm here to make your day a little easier. "
      "This morning I checked your calendar: you have a meeting at ten with the sales team, "
      "and after lunch you need to call the bank about your card. "
      "Three important emails also arrived, one of them from your client in New York asking for a quote by Friday. "
      "If you'd like, I can draft a reply right now, and you just review it before sending. "
      "Oh, and don't forget: it's going to rain quite a bit tomorrow evening, so it might be a good idea to leave work early. "
      "Should I set a reminder for four thirty?")
SHORT_PT = "Claro, já estou vendo isso para você."
SHORT_EN = "Sure, I'm looking into that for you."
RESULTS = []

def log(*a): print(*a, flush=True)

def save(name, audio, sr):
    audio = np.asarray(audio, dtype=np.float32).flatten()
    audio = audio / max(1e-6, float(np.max(np.abs(audio)))) * 0.95
    wav = f"{OUT}/{name}.wav"; sf.write(wav, audio, sr)
    mp3 = f"{OUT}/{name}.mp3"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-b:a", "128k", mp3], check=False)
    if os.path.exists(mp3): os.remove(wav)
    return len(audio) / sr

def run(name, lang, size_mb, synth, short, long_text):
    """synth(text) -> (audio, sr). Mede 1º áudio (frase curta, 2ª chamada = aquecido) e RTF no texto longo."""
    try:
        synth(short)  # aquecimento
        t = time.perf_counter(); a, sr = synth(short); first = time.perf_counter() - t
        t = time.perf_counter(); a, sr = synth(long_text); gen = time.perf_counter() - t
        dur = save(name, a, sr)
        r = {"name": name, "lang": lang, "size_mb": round(size_mb, 1), "first_audio_s": round(first, 3),
             "rtf": round(gen / dur, 3), "dur_s": round(dur, 1), "sr": sr}
        log(json.dumps(r)); RESULTS.append(r)
    except Exception:
        log("FALHOU", name); traceback.print_exc()
        RESULTS.append({"name": name, "error": traceback.format_exc()[-600:]})

def mb(*paths): return sum(os.path.getsize(p) for p in paths if os.path.exists(p)) / 1e6

which = sys.argv[1]
from huggingface_hub import hf_hub_download

if which == "kokoro":
    from kokoro_onnx import Kokoro
    import urllib.request
    base = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"
    for f in ["kokoro-v1.0.onnx", "kokoro-v1.0.int8.onnx", "voices-v1.0.bin"]:
        if not os.path.exists(f): urllib.request.urlretrieve(base + f, f)
    for model in ["kokoro-v1.0.int8.onnx", "kokoro-v1.0.onnx"]:
        k = Kokoro(model, "voices-v1.0.bin"); tag = "int8" if "int8" in model else "fp32"
        for voice, lang, txt, short in [("pf_dora", "pt-br", PT, SHORT_PT), ("pm_alex", "pt-br", PT, SHORT_PT), ("pm_santa", "pt-br", PT, SHORT_PT),
                                        ("af_heart", "en-us", EN, SHORT_EN), ("am_michael", "en-us", EN, SHORT_EN)]:
            if tag == "fp32" and voice not in ("pf_dora", "af_heart"): continue
            run(f"kokoro_{tag}_{voice}", lang, mb(model, "voices-v1.0.bin"),
                lambda t, v=voice, l=lang: k.create(t, voice=v, speed=1.0, lang=l), short, txt)

elif which == "piper":
    from piper import PiperVoice
    from onnxruntime.quantization import quantize_dynamic, QuantType
    voices = [("rhasspy/piper-voices", "pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx"),
              ("rhasspy/piper-voices", "pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx"),
              ("rhasspy/piper-voices", "pt/pt_BR/jeff/medium/pt_BR-jeff-medium.onnx"),
              ("rhasspy/piper-voices", "en/en_US/amy/medium/en_US-amy-medium.onnx"),
              ("Lucasllfs/Razo-piper-voice", None)]
    for repo, path in voices:
        try:
            if path is None:
                from huggingface_hub import list_repo_files
                path = next(f for f in list_repo_files(repo) if f.endswith(".onnx"))
            m = hf_hub_download(repo, path); c = hf_hub_download(repo, path + ".json")
        except Exception:
            log("sem modelo", repo, path); traceback.print_exc(); continue
        name = os.path.basename(path).replace(".onnx", "")
        en = "en_US" in name
        q = f"{name}.int8.onnx"; quantize_dynamic(m, q, weight_type=QuantType.QUInt8)
        for tag, mp in [("fp32", m), ("int8", q)]:
            v = PiperVoice.load(mp, config_path=c)
            def synth(t, v=v):
                chunks = list(v.synthesize(t))
                return np.concatenate([ch.audio_float_array for ch in chunks]), v.config.sample_rate
            run(f"piper_{tag}_{name}", "en" if en else "pt-br", mb(mp), synth, SHORT_EN if en else SHORT_PT, EN if en else PT)

elif which == "pocket":
    from pocket_tts import TTSModel
    for lang, txt, short in [("portuguese", PT, SHORT_PT), ("english", EN, SHORT_EN)]:
        try:
            m = TTSModel.load_model(language=lang) if "language" in TTSModel.load_model.__code__.co_varnames else TTSModel.load_model()
        except Exception:
            traceback.print_exc(); continue
        for voice in (["rafael"] if lang == "portuguese" else ["alba"]):
            try: st = m.get_state_for_audio_prompt(voice)
            except Exception: traceback.print_exc(); continue
            run(f"pocket_{lang}_{voice}", lang, 400, lambda t, st=st: (m.generate_audio(st, t).numpy(), m.sample_rate), short, txt)

elif which == "phonon":
    import ptts
    tts = ptts.TTS(lang="en")
    run("phonon31m_en_default", "en", 60, lambda t: (np.asarray(tts.synth(t)), getattr(tts, "sample_rate", 24000)), SHORT_EN, EN)
    try:
        tp = ptts.TTS(lang="pt")
        run("phonon40m_pt_default", "pt-br", 75, lambda t: (np.asarray(tp.synth(t)), getattr(tp, "sample_rate", 24000)), SHORT_PT, PT)
    except Exception:
        log("phonon pt indisponível (modelo restrito no Hugging Face)"); traceback.print_exc()

json.dump(RESULTS, open(f"{OUT}/report_{which}.json", "w"), indent=2, ensure_ascii=False)
