#!/usr/bin/env python3
"""从 LibreMath 的公开数据中提取简短定义，保留每条内容的来源与许可。"""
import json
import re
import sys
from pathlib import Path

index_path, full_path, output_path = map(Path, sys.argv[1:4])
index = json.loads(index_path.read_text(encoding="utf-8"))
full = full_path.read_text(encoding="utf-8")
segments = re.split(r"(?m)^<!-- SLUG: (.*?) -->\s*$", full)
articles = {segments[i]: segments[i + 1] for i in range(1, len(segments) - 1, 2)}


def excerpt(article):
    match = re.search(r"(?ms)^## 定义\s*\n(.*?)(?=^##\s|^###\s|\Z)", article)
    if not match:
        return ""
    for paragraph in re.split(r"\n\s*\n", match.group(1)):
        paragraph = " ".join(line.strip() for line in paragraph.splitlines()).strip()
        if not paragraph or paragraph.startswith(("|", "```", "!", "<", "- ", "1.")):
            continue
        if re.search(r"例[题：:]|例如|比如|下列|以下|本章|学习主线", paragraph):
            continue
        # 只保存完整的短句，不包含例题及其答案。
        sentence = re.match(r"^(.{8,55}?[。！？])", paragraph)
        if sentence:
            return sentence.group(1)
    return ""


def concept_excerpt(article, concept):
    # 从章节讲解的小节及要点提取短句，跳过自测和例题。
    body = re.split(r"(?m)^##\s*(?:自测|练习|常见问题|参考|学完本章)", article, maxsplit=1)[0]
    candidates = []
    for line in body.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "|", "摘要", "URL:", "学段", "<", "!", "```")):
            continue
        line = re.sub(r"^[-*]\s+", "", line).replace("**", "")
        if concept not in line or re.search(r"例[题：:]|例如|比如|自测|答案|下列", line):
            continue
        sentence = re.match(r"^(.{8,55}?[。！？])", line)
        if not sentence:
            continue
        value = sentence.group(1)
        candidates.append((0 if value.startswith(concept) else 1, len(value), value))
    return min(candidates)[2] if candidates else ""


records = []
for entry in index["entries"]:
    kind = entry.get("type")
    if kind == "textbook" and entry.get("textbook", {}).get("edition_slug") != "renjiao":
        continue
    if kind not in ("def", "textbook"):
        continue
    slug = entry.get("slug", "")
    article = articles.get(slug, "")
    text = excerpt(article)
    common = {
        "id": slug, "type": kind, "title": entry.get("title", ""),
        "grade": entry.get("grade"), "term": entry.get("term"),
        "url": entry.get("url", ""),
    }
    if text:
        records.append({**common, "concepts": entry.get("concepts", []), "excerpt": text})
    if kind == "textbook":
        for concept in entry.get("concepts", []):
            if len(concept) < 2:
                continue
            value = concept_excerpt(article, concept)
            if value and value != text:
                records.append({**common, "concepts": [concept], "excerpt": value})

output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(json.dumps({
    "source": "LibreMath",
    "license": "CC BY 4.0",
    "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
    "changes": "摘录知识点讲解中的完整短句，并按题目匹配展示。",
    "generatedAt": index.get("generatedAt", ""),
    "entries": records,
}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print("已整理", len(records), "条带出处的知识节选")
