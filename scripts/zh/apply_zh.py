#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Applies the Simplified Chinese localisation patch to ps-mobile.

values-zh/strings.xml is added separately (this repo push). This script:
  1. keeps zh resources in the APK (localeFilters in app/build.gradle.kts)
  2. adds CHINESE to the Language enum (settings UI renders entries)
  3. converts the hardcoded Russian OOM message to a string resource
  4. converts the hardcoded English quick-location labels to resources
  5. appends the 7 new keys to the default (Russian) string set
  6. appends the 7 new keys to the English string set

Every step is idempotent (marker-checked) and refuses ambiguous anchors.
"""
import pathlib
import sys

failures = []


def patch(name, path, marker, anchor, replacement):
    p = pathlib.Path(path)
    text = p.read_text(encoding="utf-8")
    if marker in text:
        print(f"[{name}] already applied")
        return
    count = text.count(anchor)
    if count != 1:
        print(f"::error file={path}::[{name}] anchor matched {count} times (expected 1)")
        failures.append(name)
        return
    p.write_text(text.replace(anchor, replacement, 1), encoding="utf-8")
    print(f"[{name}] patched")


# 1. aapt strips locales not listed in localeFilters.
patch(
    "locale-filters",
    "app/build.gradle.kts",
    'listOf("en", "ru", "zh")',
    'localeFilters += listOf("en", "ru")',
    'localeFilters += listOf("en", "ru", "zh")',
)

# 2. Language enum gains the Chinese option; no UI change needed.
patch(
    "language-enum",
    "app/src/main/java/com/psmobile/settings/AppSettings.kt",
    'CHINESE("zh"',
    'ENGLISH("en", R.string.settings_language_en),',
    'ENGLISH("en", R.string.settings_language_en),\n'
    '    CHINESE("zh", R.string.settings_language_zh),',
)

# 3. The OOM message was hardcoded Russian.
patch(
    "oom-string",
    "app/src/main/java/com/psmobile/AppViewModel.kt",
    "R.string.error_oom",
    'is OutOfMemoryError -> "\u041d\u0435 \u0445\u0432\u0430\u0442\u0438\u043b\u043e \u043f\u0430\u043c\u044f\u0442\u0438 \u0434\u043b\u044f \u044d\u0442\u043e\u0433\u043e \u0434\u043e\u043a\u0443\u043c\u0435\u043d\u0442\u0430"',
    "is OutOfMemoryError -> context.getString(R.string.error_oom)",
)

# 4. Quick-location labels were hardcoded English.
REPO = "app/src/main/java/com/psmobile/browser/FileBrowserRepository.kt"
patch(
    "quick-labels-map",
    REPO,
    "quickLabels",
    "    private val internalStorageLabel = appContext.getString(R.string.browser_internal_storage)",
    "    private val internalStorageLabel = appContext.getString(R.string.browser_internal_storage)\n"
    "\n"
    "    // Quick-location labels, resolved from resources so every locale ships.\n"
    "    private val quickLabels = mapOf(\n"
    "        Environment.DIRECTORY_DOWNLOADS to appContext.getString(R.string.quick_downloads),\n"
    "        Environment.DIRECTORY_DOCUMENTS to appContext.getString(R.string.quick_documents),\n"
    "        Environment.DIRECTORY_PICTURES to appContext.getString(R.string.quick_pictures),\n"
    "        Environment.DIRECTORY_DCIM to appContext.getString(R.string.quick_dcim),\n"
    "    )",
)
p = pathlib.Path(REPO)
text = p.read_text(encoding="utf-8")
if 'to "Download",' in text:
    block = (
        "        return listOf(\n"
        "            Environment.DIRECTORY_DOWNLOADS to \"Download\",\n"
        "            Environment.DIRECTORY_DOCUMENTS to \"Documents\",\n"
        "            Environment.DIRECTORY_PICTURES to \"Pictures\",\n"
        "            Environment.DIRECTORY_DCIM to \"DCIM\",\n"
        "        ).mapNotNull { (dir, label) ->"
    )
    if block not in text:
        print(f"::error file={REPO}::[quick-labels-block] anchor not found")
        failures.append("quick-labels-block")
    else:
        p.write_text(
            text.replace(
                block,
                "        return quickLabels.mapNotNull { (dir, label) ->",
                1,
            ),
            encoding="utf-8",
        )
        print("[quick-labels-block] patched")
else:
    print("[quick-labels-block] already applied")

# 5/6. The new keys must exist in the default (Russian) and English sets,
#      otherwise any non-Chinese locale would crash resolving them.
RU = {
    "settings_language_zh": "\u7b80\u4f53\u4e2d\u6587",
    "error_oom": "\u041d\u0435 \u0445\u0432\u0430\u0442\u0438\u043b\u043e \u043f\u0430\u043c\u044f\u0442\u0438 \u0434\u043b\u044f \u044d\u0442\u043e\u0433\u043e \u0434\u043e\u043a\u0443\u043c\u0435\u043d\u0442\u0430",
    "info_no_composite": "\u043d\u0435\u0442 (%1$d \u0441\u043b\u043e\u0451\u0432 \u0441\u0432\u0435\u0434\u0435\u043d\u043e)",
    "quick_downloads": "Download",
    "quick_documents": "Documents",
    "quick_pictures": "Pictures",
    "quick_dcim": "DCIM",
}
EN = {
    "settings_language_zh": "\u7b80\u4f53\u4e2d\u6587",
    "error_oom": "Not enough memory to open this document",
    "info_no_composite": "none (%1$d layers flattened)",
    "quick_downloads": "Download",
    "quick_documents": "Documents",
    "quick_pictures": "Pictures",
    "quick_dcim": "DCIM",
}
for path, new_keys in (
    ("app/src/main/res/values/strings.xml", RU),
    ("app/src/main/res/values-en/strings.xml", EN),
):
    p = pathlib.Path(path)
    text = p.read_text(encoding="utf-8")
    if 'name="error_oom"' in text:
        print(f"[strings {path}] already applied")
        continue
    if text.count("</resources>") != 1:
        print(f"::error file={path}::</resources> is not unique")
        failures.append(path)
        continue
    block = "".join(
        f'    <string name="{k}">{v}</string>\n' for k, v in new_keys.items()
    )
    p.write_text(
        text.replace("</resources>", block + "</resources>", 1),
        encoding="utf-8",
    )
    print(f"[strings {path}] added {len(new_keys)} keys")

if failures:
    print(f"::error::zh patch failed: {failures}")
    sys.exit(1)
print("zh patch: all steps ok")
