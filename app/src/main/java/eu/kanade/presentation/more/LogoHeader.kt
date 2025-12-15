package eu.kanade.presentation.more

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
fun LogoHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val logo = if (isSystemInDarkTheme()) {
            R.drawable.ic_gexu_logo_white
        } else {
            R.drawable.ic_gexu_logo_black
        }

        androidx.compose.foundation.Image(
            painter = painterResource(logo),
            contentDescription = null,
            modifier = Modifier
                .padding(vertical = 56.dp)
                .size(140.dp),
        )

        HorizontalDivider()
    }
}
