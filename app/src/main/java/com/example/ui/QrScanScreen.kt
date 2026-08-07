package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.ui.theme.LocalAppPalette
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * 全螢幕 QR Code 掃描 dialog。
 * 掃到有效 URL（http://…）後呼叫 onResult，否則呼叫 onDismiss。
 */
@Composable
fun QrScanDialog(
    onResult: (url: String) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalAppPalette.current
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCam = it
    }
    LaunchedEffect(Unit) {
        if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA)
    }

    // 避免連掃多次
    var done by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      com.example.ui.ReapplyAppLocale {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasCam) {
                val executor = remember { Executors.newSingleThreadExecutor() }
                val scanner = remember { BarcodeScanning.getClient() }
                // 相機綁在 Activity lifecycle 上，Dialog 關閉不會自動解绑 →
                // 沒有這段的話，掃完 QR 相機會繼續開著（隱私 + 耗電），executor/scanner 也洩漏。
                val camProvider = remember { arrayOfNulls<ProcessCameraProvider>(1) }
                DisposableEffect(Unit) {
                    onDispose {
                        runCatching { camProvider[0]?.unbindAll() }
                        runCatching { scanner.close() }
                        executor.shutdown()
                    }
                }

                AndroidView(
                    factory = { c ->
                        val pv = PreviewView(c)
                        val providerFuture = ProcessCameraProvider.getInstance(c)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(pv.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(Size(1280, 720))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(executor) { proxy ->
                                if (done) { proxy.close(); return@setAnalyzer }
                                val mediaImage = proxy.image
                                if (mediaImage != null) {
                                    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                                    scanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            for (bc in barcodes) {
                                                val raw = bc.rawValue ?: continue
                                                if (bc.format == Barcode.FORMAT_QR_CODE && raw.startsWith("http")) {
                                                    done = true
                                                    onResult(raw)
                                                    break
                                                }
                                            }
                                        }
                                        .addOnCompleteListener { proxy.close() }
                                } else {
                                    proxy.close()
                                }
                            }
                            camProvider[0] = provider
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }, ContextCompat.getMainExecutor(c))
                        pv
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 掃描框提示
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    Text(
                        com.example.ui.I18n.tr("對準電腦語音中心顯示的 QR Code"),
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 100.dp)
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(com.example.ui.I18n.tr("需要相機權限"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 關閉按鈕
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            ) {
                Icon(Icons.Default.Close, contentDescription = com.example.ui.I18n.tr("關閉視窗"), tint = Color.White)
            }
        }
      }
    }
}
