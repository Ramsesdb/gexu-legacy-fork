package tachiyomi.core.common.i18n

import android.content.Context
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.Plural
import dev.icerock.moko.resources.desc.PluralFormatted
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc

fun Context.stringResource(resource: StringResource): String = StringDesc.Resource(resource).toString(this).fixed()

fun Context.stringResource(
    resource: StringResource,
    vararg args: Any,
): String = StringDesc.ResourceFormatted(resource, *args).toString(this).fixed()

fun Context.pluralStringResource(
    resource: PluralsResource,
    count: Int,
): String = StringDesc.Plural(resource, count).toString(this).fixed()

fun Context.pluralStringResource(
    resource: PluralsResource,
    count: Int,
    vararg args: Any,
): String = StringDesc.PluralFormatted(resource, count, *args).toString(this).fixed()

// TODO: janky workaround for https://github.com/icerockdev/moko-resources/issues/337
private fun String.fixed() = this.replace("""\""", """"""")

