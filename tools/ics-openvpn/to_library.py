"""ics-openvpn :main — приложение; для AKULA VPN нужна библиотека (.aar) с движком OpenVPN 2 (вариант skeleton+ovpn2).
Правим build.gradle.kts внутри контейнера сборки: убираем всё, что бывает только у приложения."""
import re, sys
p = sys.argv[1]
t = open(p, encoding="utf-8").read()

def cut_block(text, header):
    """Вырезать блок «header { ... }» с учётом вложенных скобок."""
    i = text.find(header)
    if i < 0:
        return text
    j = text.index("{", i); depth = 0
    for k in range(j, len(text)):
        depth += {"{": 1, "}": -1}.get(text[k], 0)
        if depth == 0:
            return text[:i] + text[k + 1:]
    raise SystemExit(f"не закрыт блок {header}")

# Плагин библиотеки — без версии: его уже подгрузил корневой build.gradle.kts (через плагин приложения)
t = t.replace("alias(libs.plugins.android.application)", 'id("com.android.library")')
t = re.sub(r"\n\s*(targetSdk|versionCode|versionName) = [^\n]+", "", t)
t = re.sub(r'\n\s*versionNameSuffix = "[^"]*"', "", t)
for h in ("signingConfigs {", "splits {", "bundle {"):
    t = cut_block(t, h)
t = re.sub(r'if \(project\.hasProperty\("icsopenvpnDebugSign"\)\) \{.*?\n            \}\n', "", t, flags=re.S)
assert "signingConfig" not in t, "остались подписи"
open(p, "w", encoding="utf-8").write(t)

toml = sys.argv[2]
v = open(toml, encoding="utf-8").read()
if "android-library" not in v:
    v = v.replace('android-application = { id = "com.android.application", version.ref = "android-gradle-plugin" }',
                  'android-application = { id = "com.android.application", version.ref = "android-gradle-plugin" }\n'
                  'android-library = { id = "com.android.library", version.ref = "android-gradle-plugin" }')
    open(toml, "w", encoding="utf-8").write(v)
print("ok: библиотека")
