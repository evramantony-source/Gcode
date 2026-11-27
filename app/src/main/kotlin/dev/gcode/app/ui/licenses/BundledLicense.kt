package dev.gcode.app.ui.licenses

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import dev.gcode.app.R
import kotlinx.serialization.Serializable

/** Full licence texts shipped inside the app (res/raw). */
@Serializable
enum class BundledLicense(@StringRes val title: Int, @RawRes val text: Int) {
    APACHE_2_0(R.string.license_apache_title, R.raw.license_apache_2_0),
    LGPL_2_1(R.string.licenses_lgpl_title, R.raw.license_lgpl_2_1),
}
