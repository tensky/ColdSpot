package id.tensky.coldspot.feature

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview

/** A multipreview, light and dark, defined here and used by the app: the preview lookup has to cross modules to see it. */
@Preview(name = "light")
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES)
annotation class ThemePreviews
