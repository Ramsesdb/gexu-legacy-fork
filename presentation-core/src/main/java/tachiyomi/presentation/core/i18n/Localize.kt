package tachiyomi.presentation.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource

@Composable
@ReadOnlyComposable
fun stringResource(resource: StringResource): String = LocalContext.current.stringResource(resource)

@Composable
@ReadOnlyComposable
fun stringResource(
    resource: StringResource,
    vararg args: Any,
): String = LocalContext.current.stringResource(resource, *args)

@Composable
@ReadOnlyComposable
fun pluralStringResource(
    resource: PluralsResource,
    count: Int,
): String = LocalContext.current.pluralStringResource(resource, count)

@Composable
@ReadOnlyComposable
fun pluralStringResource(
    resource: PluralsResource,
    count: Int,
    vararg args: Any,
): String = LocalContext.current.pluralStringResource(resource, count, *args)

