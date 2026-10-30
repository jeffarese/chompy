package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// On a tablet, or a phone unfolded or turned sideways, cards and lines of text stretched edge to edge
// are hard to read: past this width, a screen's content stays this wide, centered.

/** The widest a screen's content gets. */
val ContentMaxWidth = 640.dp

/** At most [ContentMaxWidth] wide, centered in the width there is. */
fun Modifier.readableWidth(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth)

/**
 * A list's content padding: [padding] all round, and more at the sides on a wide screen, so its items
 * are at most [ContentMaxWidth] wide and centered while a drag anywhere across the screen still scrolls it.
 */
@Composable
fun readableListPadding(padding: Dp = 16.dp): PaddingValues {
    val width = LocalConfiguration.current.screenWidthDp.dp
    return PaddingValues(horizontal = maxOf(padding, (width - ContentMaxWidth) / 2), vertical = padding)
}
