import base64, json, os, zipfile
from PIL import Image, ImageDraw, PngImagePlugin

out = r"D:/Project/TeaTalkApp/.tools/cards/edge"
os.makedirs(out, exist_ok=True)

def png(path, texts, size=(256, 256), color=(180, 140, 100)):
    img = Image.new("RGB", size, color)
    d = ImageDraw.Draw(img)
    d.rectangle([8, 8, size[0]-8, size[1]-8], outline=(90, 70, 55), width=4)
    info = PngImagePlugin.PngInfo()
    for k, v in texts.items():
        info.add_text(k, v)
    img.save(path, pnginfo=info)
    return os.path.getsize(path)

def v2data(**over):
    d = {
        "name": "边界测试卡", "description": "desc", "personality": "p",
        "scenario": "s", "first_mes": "hi", "mes_example": "",
        "creator_notes": "", "system_prompt": "", "post_history_instructions": "",
        "alternate_greetings": [], "tags": ["测试"], "creator": "edge",
        "character_version": "1.0", "extensions": {},
    }
    d.update(over)
    return {"spec": "chara_card_v2", "spec_version": "2.0", "data": d}

long_name = "超长名字" + "·" * 150 + "结尾"
s1 = png(os.path.join(out, "01-long-name.png"), {"chara": base64.b64encode(json.dumps(v2data(name=long_name), ensure_ascii=False).encode()).decode()})
print("01-long-name", s1)

entries = []
for i in range(500):
    entries.append({
        "id": i, "keys": [f"关键词{i}", f"key{i}"], "secondary_keys": [],
        "comment": f"条目{i}", "content": "世界书内容" * 40, "constant": False,
        "selective": True, "insertion_order": i, "enabled": True,
        "position": "before_char", "extensions": {},
    })
book = {"extensions": {}, "entries": entries}
v3 = {"spec": "chara_card_v3", "spec_version": "3.0",
      "data": dict(v2data(name="超大世界书")["data"], character_book=book)}
s2 = png(os.path.join(out, "02-huge-worldbook.png"), {"chara": base64.b64encode(json.dumps(v3, ensure_ascii=False).encode()).decode()})
print("02-huge-worldbook", s2)

big = v2data(name="超长设定", description="设定" * 120000)
s3 = png(os.path.join(out, "03-huge-description.png"), {"chara": base64.b64encode(json.dumps(big, ensure_ascii=False).encode()).decode()})
print("03-huge-description", s3)

# CHARX: zip with card.json
cardx = os.path.join(out, "04-charx.charx")
with zipfile.ZipFile(cardx, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("card.json", json.dumps(v3, ensure_ascii=False))
    z.writestr("assets/avatar.png", b"\x89PNG\r\n\x1a\n" + b"0" * 64)
print("04-charx", os.path.getsize(cardx))

s5 = png(os.path.join(out, "05-no-metadata.png"), {})
print("05-no-metadata", s5)

s6 = png(os.path.join(out, "06-bad-base64.png"), {"chara": "这不是base64!!!"})
print("06-bad-base64", s6)

with open(os.path.join(out, "07-missing-name.json"), "w", encoding="utf-8") as f:
    json.dump({"spec": "chara_card_v2", "spec_version": "2.0", "data": {"description": "没有名字"}}, f, ensure_ascii=False)
print("07-missing-name", os.path.getsize(os.path.join(out, "07-missing-name.json")))

with open(os.path.join(out, "08-bom-crlf.json"), "wb") as f:
    f.write(b"\xef\xbb\xbf" + json.dumps(v2data(name="BOM与CRLF"), ensure_ascii=False, indent=2).replace("\n", "\r\n").encode("utf-8"))
print("08-bom-crlf", os.path.getsize(os.path.join(out, "08-bom-crlf.json")))

big_avatar = png(os.path.join(out, "09-big-avatar.png"),
                 {"chara": base64.b64encode(json.dumps(v2data(name="4K头像"), ensure_ascii=False).encode()).decode(),
                  "ccv3": base64.b64encode(json.dumps(v3, ensure_ascii=False).encode()).decode()},
                 size=(2048, 2048), color=(60, 120, 90))
print("09-big-avatar", big_avatar)

s10 = png(os.path.join(out, "10-only-ccv3.png"), {"ccv3": base64.b64encode(json.dumps(v3, ensure_ascii=False).encode()).decode()})
print("10-only-ccv3", s10)
s11 = png(os.path.join(out, "11-only-chara-v3.png"), {"chara": base64.b64encode(json.dumps(v3, ensure_ascii=False).encode()).decode()})
print("11-only-chara-v3", s11)
weird = "🄰" * 30 + "‮RTL‬" + "e\u0301\u0301\u0301" + "名字" * 60
s12 = png(os.path.join(out, "12-weird-unicode.png"), {"chara": base64.b64encode(json.dumps(v2data(name=weird), ensure_ascii=False).encode()).decode()})
print("12-weird-unicode", s12)
print("done")
