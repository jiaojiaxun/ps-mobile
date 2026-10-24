# 简体中文汉化补丁

基于 JustRin/ps-mobile，由 `scripts/zh/apply_zh.py` 在构建时应用：

- 新增 `app/src/main/res/values-zh/strings.xml`（162 条，覆盖全部 155 个原有键 + 7 个新键）
- `localeFilters` 加入 `zh`（否则 aapt 会剥离中文资源）
- `Language` 枚举加 `CHINESE("zh")`，设置页自动出现「简体中文」选项
- OOM 提示（原硬编码俄语）与快捷位置名（原硬编码英文）收编为资源
- 默认（俄语）与英语字符串集补齐 7 个新键

中文系统开箱即中文；其他系统可在 设置 → 界面语言 选择。
