"""Inspect the packaged APK against the exact merged, user-selectable catalog entry."""
import json
import re
import sys
import zipfile
from pathlib import Path

repo=Path(__file__).resolve().parents[2]
source=repo/"android/app/src/main/java/com/vocahq/vocaphone/local/SherpaModelCatalog.kt"
kotlin=source.read_text().split('id = "oruk-fourier-2b",',1)[1].split("\n        sherpa(",1)[0]
revision=re.search(r'revision = "([a-f0-9]+)"',kotlin)[1]
pins=re.findall(r'PinnedFile\("([^"]+)",\s*([0-9_]+)L,\s*"([a-f0-9]{64})"',kotlin)
assert len(pins)==10
assert sum(int(size.replace("_","")) for _,size,_ in pins)==2409908809
with zipfile.ZipFile(sys.argv[1]) as apk:
    pieces=b"".join(apk.read(n) for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex",n))
    assert all(v.encode() in pieces for v in ("oruk-fourier-2b","Oruk Fourier 2B","oruk/oruk-fourier-2b",revision,"onnx/sherpa-int8","English"))
    for name,_,digest in pins:
        assert name.encode() in pieces and digest.encode() in pieces,name
    assert all(any(x.startswith("lib/arm64-v8a/") and x.endswith(lib) for x in apk.namelist()) for lib in ["libonnxruntime.so","libsherpa-onnx-jni.so"])
    weights=[n for n in apk.namelist() if n.lower().endswith((".onnx",".onnx.data",".gguf",".safetensors",".mlmodelc",".tflite",".pt"))]
    assert not weights
    assert "assets/preview-notices/VocaPhone-AGPL-3.0.txt" in apk.namelist()
result={"upstream":"6f1f5cd21159ac90a0190aa2b507e3deca837c97","package":"ai.oruk.fourierpreview.keyboard","model":"Oruk Fourier 2B","english_only":True,"bytes":2409908809,"model_files_packaged":weights,"files":[{"path":n,"size":int(size.replace("_","")),"sha256":h,"source":"https://huggingface.co/oruk/oruk-fourier-2b/resolve/"+revision+"/onnx/sherpa-int8/"+n} for n,size,h in pins]}
Path("distribution/package-verification.json").write_text(json.dumps(result,indent=2)+"\n")
print("Verified ten paths and SHA256s, revision, English label, native ARM64 engines and zero bundled weights.")
