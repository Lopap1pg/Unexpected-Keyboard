#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str, marker: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if marker in text:
        return
    if old not in text:
        raise RuntimeError(f"Expected source block not found in {path}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Wire the Japanese router in front of the existing KeyEventHandler.
replace_once(
    "srcs/juloo.keyboard2/Keyboard2.java",
    "  private KeyEventHandler _keyeventhandler;\n",
    "  private KeyEventHandler _keyeventhandler;\n"
    "  private JapaneseKeyEventHandler _japanese_keyeventhandler;\n",
    "private JapaneseKeyEventHandler _japanese_keyeventhandler;",
)

replace_once(
    "srcs/juloo.keyboard2/Keyboard2.java",
    "    _keyeventhandler = new KeyEventHandler(_config, recvr, _suggestions);\n"
    "    KeyValue.Stateful._handler = recvr;\n"
    "    _config.handler = _keyeventhandler;\n",
    "    _keyeventhandler = new KeyEventHandler(_config, recvr, _suggestions);\n"
    "    _japanese_keyeventhandler = new JapaneseKeyEventHandler(\n"
    "        this, _keyeventhandler, _suggestions, recvr,\n"
    "        new Runnable()\n"
    "        {\n"
    "          @Override\n"
    "          public void run()\n"
    "          {\n"
    "            refresh_candidates_view();\n"
    "          }\n"
    "        });\n"
    "    KeyValue.Stateful._handler = recvr;\n"
    "    _config.handler = _japanese_keyeventhandler;\n",
    "_config.handler = _japanese_keyeventhandler;",
)

replace_once(
    "srcs/juloo.keyboard2/Keyboard2.java",
    "    _keyeventhandler.started();\n"
    "    setInputView(_keyboard_container_view);\n",
    "    _keyeventhandler.started();\n"
    "    _japanese_keyeventhandler.started();\n"
    "    setInputView(_keyboard_container_view);\n",
    "_japanese_keyeventhandler.started();",
)

replace_once(
    "srcs/juloo.keyboard2/Keyboard2.java",
    "    boolean should_show =\n"
    "      _config.suggestions_enabled\n"
    "      && _config.editor_config.should_show_candidates_view\n",
    "    boolean should_show =\n"
    "      (_config.suggestions_enabled\n"
    "       || (_japanese_keyeventhandler != null\n"
    "           && _japanese_keyeventhandler.is_japanese_enabled()))\n"
    "      && _config.editor_config.should_show_candidates_view\n",
    "_japanese_keyeventhandler.is_japanese_enabled()",
)

# Add protobuf-lite and generated Mozc inputs to the Android build.
replace_once(
    "build.gradle.kts",
    "import com.android.build.gradle.internal.api.BaseVariantOutputImpl\n",
    "import com.google.protobuf.gradle.id\n"
    "import com.android.build.gradle.internal.api.BaseVariantOutputImpl\n",
    "import com.google.protobuf.gradle.id",
)

replace_once(
    "build.gradle.kts",
    "plugins {\n"
    "  id(\"com.android.application\") version \"8.13.2\"\n"
    "}\n",
    "plugins {\n"
    "  id(\"com.android.application\") version \"8.13.2\"\n"
    "  id(\"com.google.protobuf\") version \"0.9.4\"\n"
    "}\n",
    "id(\"com.google.protobuf\") version \"0.9.4\"",
)

replace_once(
    "build.gradle.kts",
    "  implementation(\"androidx.core:core:1.16.0\") // Version 1.17.0 available with sdk 36\n"
    "  testImplementation(\"junit:junit:4.13.2\")\n",
    "  implementation(\"androidx.core:core:1.16.0\") // Version 1.17.0 available with sdk 36\n"
    "  implementation(\"com.google.protobuf:protobuf-javalite:4.28.3\")\n"
    "  testImplementation(\"junit:junit:4.13.2\")\n",
    "protobuf-javalite:4.28.3",
)

replace_once(
    "build.gradle.kts",
    "dependencies {\n"
    "  // Following versions of androidx.window require sdk version 23\n"
    "  implementation(\"androidx.window:window-java:1.4.0\")\n"
    "  implementation(\"androidx.core:core:1.16.0\") // Version 1.17.0 available with sdk 36\n"
    "  implementation(\"com.google.protobuf:protobuf-javalite:4.28.3\")\n"
    "  testImplementation(\"junit:junit:4.13.2\")\n"
    "}\n\n",
    "dependencies {\n"
    "  // Following versions of androidx.window require sdk version 23\n"
    "  implementation(\"androidx.window:window-java:1.4.0\")\n"
    "  implementation(\"androidx.core:core:1.16.0\") // Version 1.17.0 available with sdk 36\n"
    "  implementation(\"com.google.protobuf:protobuf-javalite:4.28.3\")\n"
    "  testImplementation(\"junit:junit:4.13.2\")\n"
    "}\n\n"
    "protobuf {\n"
    "  protoc {\n"
    "    artifact = \"com.google.protobuf:protoc:4.28.3\"\n"
    "  }\n"
    "  generateProtoTasks {\n"
    "    all().configureEach {\n"
    "      builtins {\n"
    "        id(\"java\") { option(\"lite\") }\n"
    "      }\n"
    "    }\n"
    "  }\n"
    "}\n\n",
    "artifact = \"com.google.protobuf:protoc:4.28.3\"",
)

replace_once(
    "build.gradle.kts",
    "      java.srcDirs(\"srcs/juloo.keyboard2\", \"vendor/cdict/java/juloo.cdict\")\n"
    "      res.srcDirs(\"res\", \"build/generated-resources\")\n"
    "      assets.srcDirs(\"assets\")\n",
    "      java.srcDirs(\"srcs/juloo.keyboard2\", \"srcs/mozc/java\", \"vendor/cdict/java/juloo.cdict\")\n"
    "      res.srcDirs(\"res\", \"build/generated-resources\")\n"
    "      assets.srcDirs(\"assets\", \"build/mozc-generated/assets\")\n"
    "      jniLibs.srcDirs(\"build/mozc-generated/jniLibs\")\n"
    "      proto.srcDir(\"build/mozc-generated/proto\")\n",
    "build/mozc-generated/jniLibs",
)

# Build Mozc before Java/protobuf compilation in CI.
replace_once(
    ".github/workflows/make-apk.yml",
    "    - name: Run tests\n"
    "      run: gradle test\n",
    "    - name: Install Bazelisk\n"
    "      run: npm install -g @bazel/bazelisk\n\n"
    "    - name: Build Mozc engine\n"
    "      run: bash scripts/build_mozc.sh\n\n"
    "    - name: Run tests\n"
    "      run: gradle test\n",
    "name: Build Mozc engine",
)

print("Japanese IME source integration applied")
