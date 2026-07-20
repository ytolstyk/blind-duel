package com.tolstykh.blindduel.ui.create

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

@Composable
fun CreateScreen(
    onConnected: () -> Unit,
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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
