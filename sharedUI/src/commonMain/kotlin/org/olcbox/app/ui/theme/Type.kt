package org.olcbox.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.manrope_400
import multiplatform_app.sharedui.generated.resources.manrope_500
import multiplatform_app.sharedui.generated.resources.manrope_600
import multiplatform_app.sharedui.generated.resources.manrope_700
import multiplatform_app.sharedui.generated.resources.unbounded_600
import multiplatform_app.sharedui.generated.resources.unbounded_700
import org.jetbrains.compose.resources.Font

@Composable
fun getAppTypography(): Typography {
    // Шрифты AKULA (как сайт и консоль): Manrope — текст, Unbounded — заголовки
    val googleSansFlex = FontFamily(
        Font(Res.font.manrope_400, weight = FontWeight.Normal),
        Font(Res.font.manrope_500, weight = FontWeight.Medium),
        Font(Res.font.manrope_600, weight = FontWeight.SemiBold),
        Font(Res.font.manrope_700, weight = FontWeight.Bold)
    )
    val unbounded = FontFamily(
        Font(Res.font.unbounded_600, weight = FontWeight.SemiBold),
        Font(Res.font.unbounded_700, weight = FontWeight.Bold)
    )

    val defaultTypography = Typography()

    return Typography(
        displayLarge = defaultTypography.displayLarge.copy(fontFamily = unbounded),
        displayMedium = defaultTypography.displayMedium.copy(fontFamily = unbounded),
        displaySmall = defaultTypography.displaySmall.copy(fontFamily = unbounded),
        headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = unbounded),
        headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = unbounded),
        headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = unbounded),
        titleLarge = defaultTypography.titleLarge.copy(fontFamily = googleSansFlex),
        titleMedium = defaultTypography.titleMedium.copy(fontFamily = googleSansFlex),
        titleSmall = defaultTypography.titleSmall.copy(fontFamily = googleSansFlex),
        bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = googleSansFlex),
        bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = googleSansFlex),
        bodySmall = defaultTypography.bodySmall.copy(fontFamily = googleSansFlex),
        labelLarge = defaultTypography.labelLarge.copy(fontFamily = googleSansFlex),
        labelMedium = defaultTypography.labelMedium.copy(fontFamily = googleSansFlex),
        labelSmall = defaultTypography.labelSmall.copy(fontFamily = googleSansFlex)
    )
}
