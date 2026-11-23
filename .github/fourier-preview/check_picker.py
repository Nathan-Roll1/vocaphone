"""Check the installed preview's production onboarding and model picker on a fresh emulator."""
import json, re, subprocess as sp, time
from pathlib import Path
from xml.etree import ElementTree as ET

out=Path("distribution")
def adb(*args):
    return sp.check_output(["adb",*args],timeout=25).decode().strip()
def screen():
    adb("shell","uiautomator","dump","/sdcard/uifourier.xml")
    data=adb("shell","cat","/sdcard/uifourier.xml")
    root=ET.fromstring(data[data.index("<?xml"):])
    return [n.attrib for n in root.iter("node")]
def title(n):return (n.get("text","")+" "+n.get("content-desc","")).strip()
def select(n):
    a,b,c,d=map(int,re.findall(r"\d+",n["bounds"]))
    adb("shell","input","tap",str((a+c)//2),str((b+d)//2))
def screenshot(name):
    (out/name).write_bytes(sp.check_output(["adb","exec-out","screencap","-p"]))
package="ai.oruk.fourierpreview.keyboard"
abis=adb("shell","getprop","ro.product.cpu.abilist")
ram=adb("shell","cat","/proc/meminfo").splitlines()[0]
print("EMULATOR",abis,ram,flush=True)
adb("shell","am","start","-n",package+"/com.vocahq.vocaphone.ui.MainActivity")
time.sleep(4)
entered=expanded=False
for i in range(42):
    nodes=screen()
    labels=[title(n) for n in nodes if title(n)]
    print(i,labels[:28],flush=True)
    if any("Oruk license and workplace permission" in t for t in labels) and any("English" in t for t in labels):
        screenshot("picker.png")
        (out/"picker.json").write_text(json.dumps({"package":package,"device_abis":abis,"device_memory":ram,"labels":labels,"download_clicked":False,"microphone_test":False},indent=2))
        break
    fourier=next((n for n in nodes if "Oruk Fourier 2B" in title(n)),None)
    if fourier and not expanded:
        screenshot("listed.png");select(fourier);expanded=True;time.sleep(.6);continue
    if expanded:
        if any("English" in t for t in labels) and any("Oruk Fourier 2B" in t for t in labels):
            screenshot("picker.png")
            (out/"picker.json").write_text(json.dumps({"package":package,"device_abis":abis,"device_memory":ram,"labels":labels,"download_clicked":False,"microphone_test":False},indent=2))
            break
    if not entered:
        edit=next((n for n in nodes if n["class"].endswith("EditText")),None)
        if edit and (any("Find a model" in t for t in labels) or any("Name, language" in t for t in labels)):
            select(edit);adb("shell","input","text","Fourier");adb("shell","input","keyevent","4");entered=True;time.sleep(1);continue
    options=["Get started","Continue","All models"]
    candidates=[n for s in options for n in nodes if title(n)==s]
    candidates += [n for n in nodes if re.match(r"See all \d+ models",title(n))]
    if candidates and not entered:select(candidates[0]);time.sleep(.7);continue
    if entered and not fourier:
        (out/"filtered-picker.json").write_text(json.dumps(labels,indent=2));screenshot("filtered.png")
        if any("No models" in t for t in labels):break
    if i>2:
        size=adb("shell","wm","size")
        x,y=map(int,re.findall(r"(\d+)x(\d+)",size)[-1])
        adb("shell","input","swipe",str(x//2),str(int(y*.78)),str(x//2),str(int(y*.33)),"360")
    time.sleep(.5)
else:screenshot("last.png")
assert (out/"picker.json").exists(),"The production picker did not expose the Fourier entry on this emulator"
