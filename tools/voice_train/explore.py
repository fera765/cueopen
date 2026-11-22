from huggingface_hub import HfApi
api = HfApi()
for q in ["tts-portuguese", "TTS-Portuguese-Corpus", "portuguese tts corpus", "edresson"]:
    for d in api.list_datasets(search=q, limit=20): print("DS", q, d.id)
for v in ["faber", "cadu"]:
    for f in api.list_repo_files("rhasspy/piper-checkpoints", repo_type="dataset"):
        if f"pt_BR/{v}" in f: print("CK", f)
