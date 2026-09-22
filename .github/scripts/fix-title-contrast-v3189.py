#!/usr/bin/env python3
"""One-time v3.1.89 source fix; fail rather than editing unexpected revisions."""
from pathlib import Path


def replace_exact(path: str, before: str, after: str) -> None:
    file = Path(path)
    data = file.read_text(encoding="utf-8")
    count = data.count(before)
    if count != 1:
        raise RuntimeError(f"Expected exactly one matching location in {path}; found {count}: {before[:80]!r}")
    file.write_text(data.replace(before, after, 1), encoding="utf-8")


theme = "app/src/main/java/com/aliflix/app/ui/theme/AliflixTheme.kt"
app = "app/src/main/java/com/aliflix/app/ui/AliflixApp.kt"
version = "app/build.gradle.kts"
release = ".github/workflows/mobile-release.yml"

# Unspecified Compose Text and Icon colors inherited black from the host.
# Scope the correction to the mobile theme; Material3 controls can still override it.
replace_exact(theme,
              "import androidx.compose.runtime.Composable\n",
              "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.CompositionLocalProvider\nimport androidx.compose.material3.LocalContentColor\n")
replace_exact(theme,
              "        shapes = AliflixShapes,\n        content = content,\n",
              "        shapes = AliflixShapes,\n        content = {\n            CompositionLocalProvider(LocalContentColor provides AliflixContentPrimary) {\n                content()\n            }\n        },\n")

# Explicit contrast at the reported hero and in shared media tiles; keep title
# readability independent of the parent Surface and animation container.
replace_exact(app,
              "                        text = item.title,\n                        style = MaterialTheme.typography.displayMedium,\n                        fontWeight = FontWeight.Black,\n",
              "                        text = item.title,\n                        color = AliflixContentPrimary,\n                        style = MaterialTheme.typography.displayMedium,\n                        fontWeight = FontWeight.Black,\n")
replace_exact(app,
              "        Text(\n            text = item.title,\n            fontSize = 13.sp,\n            fontWeight = FontWeight.SemiBold,\n            lineHeight = 17.sp,\n",
              "        Text(\n            text = item.title,\n            color = AliflixContentPrimary,\n            fontSize = 13.sp,\n            fontWeight = FontWeight.SemiBold,\n            lineHeight = 17.sp,\n")
replace_exact(app,
              "            Text(\n                text = episode.title,\n                fontWeight = FontWeight.SemiBold,\n                maxLines = 2,\n",
              "            Text(\n                text = episode.title,\n                color = AliflixContentPrimary,\n                fontWeight = FontWeight.SemiBold,\n                maxLines = 2,\n")

# Match the existing secondary-surface back-button design and guarantee a
# visible white arrow over cinematic artwork, including very dark imagery.
replace_exact(app,
              "                        .padding(16.dp)\n                        .size(48.dp)\n                        .clip(CircleShape)\n                        .background(AliflixScrimStrong)\n                        .border(1.dp, AliflixBorderStrong, CircleShape),\n",
              "                        .padding(16.dp)\n                        .size(48.dp)\n                        .clip(CircleShape)\n                        .background(AliflixSurfaceSecondary.copy(alpha = 0.96f))\n                        .border(1.dp, AliflixBorderStrong, CircleShape),\n")
replace_exact(app,
              "                        Icons.AutoMirrored.Rounded.ArrowBack,\n                        contentDescription = \"Back to previous screen\",\n",
              "                        Icons.AutoMirrored.Rounded.ArrowBack,\n                        contentDescription = \"Back to previous screen\",\n                        tint = AliflixContentPrimary,\n")

# Bump only the mobile flavor; do not change the independently versioned TV app.
replace_exact(version, 'val mobileVersionCode = 178\n', 'val mobileVersionCode = 179\n')
replace_exact(version, 'val mobileVersionName = "3.1.88"\n', 'val mobileVersionName = "3.1.89"\n')
replace_exact(release, '  RELEASE_VERSION_CODE: "178"\n', '  RELEASE_VERSION_CODE: "179"\n')
replace_exact(release, '  RELEASE_VERSION_NAME: "3.1.88"\n', '  RELEASE_VERSION_NAME: "3.1.89"\n')
replace_exact(release,
              'RELEASE_NOTES="Aliflix v${RELEASE_VERSION_NAME}: Ask Aliflix now runs GPT-OSS 120B with instant model switching and a more reliable Find More flow."',
              'RELEASE_NOTES="Aliflix v${RELEASE_VERSION_NAME}: Restore readable white titles throughout the app and improve detail back-button contrast."')
replace_exact(release,
              'body: "Ask Aliflix now runs GPT-OSS 120B with instant model switching and a more reliable Find More flow. Tests and emulator validation were explicitly skipped at user request."',
              'body: "Restore readable white titles throughout the app and improve detail back-button contrast. Tests and emulator validation were explicitly skipped at user request."')
print("Applied scoped mobile title, icon contrast, and release version changes.")
