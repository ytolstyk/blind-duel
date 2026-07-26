package com.tolstykh.blindduel.ui.create

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.tolstykh.blindduel.BuildConfig
import com.tolstykh.blindduel.game.GameConstants.WeaponConfigs
import com.tolstykh.blindduel.game.MatchLength
import com.tolstykh.blindduel.game.WeaponType

@Composable
fun CreateScreen(
    onConnected: () -> Unit,
    onNavigateToSoloTest: () -> Unit,
    viewModel: CreateViewModel = hiltViewModel(),
) {
    val qrBitmap = remember(viewModel.qrPayload) { generateQrBitmap(viewModel.qrPayload) }

    LaunchedEffect(viewModel.isConnected) {
        if (viewModel.isConnected) onConnected()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MatchLengthOptions(
            viewModel = viewModel,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        Text(text = "Have your opponent scan this code", style = MaterialTheme.typography.titleMedium)
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = "Session QR code",
            modifier = Modifier
                .padding(24.dp)
                .size(240.dp),
        )
        Text(text = "Code: ${viewModel.sessionCode}", style = MaterialTheme.typography.headlineSmall)
        Text(text = "Waiting for opponent…", modifier = Modifier.padding(top = 16.dp))

        if (BuildConfig.DEBUG) {
            SoloTestOptions(
                viewModel = viewModel,
                onStartSoloTest = {
                    viewModel.startSoloTest()
                    onNavigateToSoloTest()
                },
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

/** Host-only match-length picker — NOT debug-gated, unlike [SoloTestOptions] below, since this
 * must work in release builds. The joiner never picks their own; they always adopt whichever
 * value the host chose, via GameMessage.Hello (see JoinViewModel). Takes [viewModel] and reads
 * its state internally (matching [SoloTestOptions] below) rather than a resolved `selected`
 * value, so a match-length tap only recomposes this row — not CreateScreen's whole body, which
 * also hosts the QR bitmap and (in debug builds) SoloTestOptions. See the project's "Duel Screen
 * Recomposition Note" for why reading ViewModel state as a call argument in the parent's own
 * body would tie the parent's recompose scope to it instead. */
@Composable
private fun MatchLengthOptions(
    viewModel: CreateViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Match length",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MatchLength.entries.forEach { length ->
                FilterChip(
                    selected = viewModel.matchLength == length,
                    onClick = { viewModel.onMatchLengthSelected(length) },
                    label = { Text(length.displayName) },
                )
            }
        }
    }
}

/** Debug-only pre-game options for Solo Test Mode: pick a starting weapon (so any weapon can be
 * tested without waiting on a random crate drop) and toggle crate drops on/off, so a practice
 * session can be tuned to test a specific thing while still defaulting to look like a real match.
 * Starting a Solo Test from here abandons the real Nearby hosting this screen already started
 * (see [CreateViewModel.startSoloTest]) in favor of the local loopback "opponent". */
@Composable
private fun SoloTestOptions(
    viewModel: CreateViewModel,
    onStartSoloTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text = "Solo Test Options",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = "Starting weapon",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WeaponType.entries.forEach { weapon ->
                FilterChip(
                    selected = viewModel.soloTestWeapon == weapon,
                    onClick = { viewModel.onSoloTestWeaponChanged(weapon) },
                    label = { Text(WeaponConfigs.forType(weapon).displayName) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Weapon crates", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = viewModel.soloTestCratesEnabled,
                onCheckedChange = viewModel::onSoloTestCratesEnabledChanged,
            )
        }

        TextButton(onClick = onStartSoloTest, modifier = Modifier.padding(top = 8.dp)) {
            Text("Solo Test (Debug)")
        }
    }
}

private fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
    val bitMatrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap[x, y] = if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return bitmap
}
