package com.dalek.stickercamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import android.provider.MediaStore
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.launch
import kotlin.math.max


class StickerState(
    val id: Long = System.currentTimeMillis(),
    val resId: Int
) {
    var offsetX by mutableStateOf(0f)
    var offsetY by mutableStateOf(0f)
    var scale by mutableStateOf(1f)
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StickerCameraApp() }
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun StickerCameraApp() {

    val cameraPermission =
        rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    when {
        cameraPermission.status.isGranted -> CameraEntryScreen()
        cameraPermission.status.shouldShowRationale ->
            PermissionRationaleScreen {
                cameraPermission.launchPermissionRequest()
            }
        else -> PermissionDeniedScreen()
    }
}


@Composable
fun PermissionDeniedScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("카메라 권한이 필요합니다.")
    }
}

@Composable
fun PermissionRationaleScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("카메라 사용을 위해 권한이 필요합니다.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("다시 요청") }
        }
    }
}


@Composable
fun CameraEntryScreen() {

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val overlayView = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                imageCapture = imageCapture
            )

            OverlayLayer(
                imageCapture = imageCapture,
                overlayView = overlayView,
                onPhotoSaved = {
                    scope.launch {
                        snackbarHostState.showSnackbar("사진이 저장되었습니다")
                    }
                }
            )
        }
    }
}


@Composable
fun OverlayLayer(
    imageCapture: ImageCapture,
    overlayView: View,
    onPhotoSaved: () -> Unit
) {
    val context = LocalContext.current
    val stickers = remember { mutableStateListOf<StickerState>() }

    Box(Modifier.fillMaxSize()) {

        StickerCanvas(
            stickers = stickers,
            onRequestDelete = { /* 삭제 처리 */ }
        )

        StickerPanel(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight(),
            onStickerSelected = { resId ->
                stickers.add(
                    StickerState(resId = resId).apply {
                        offsetX = 200f
                        offsetY = 400f
                    }
                )
            }
        )

        FloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            onClick = {
                takePhotoWithOverlay(
                    context = context,
                    imageCapture = imageCapture,
                    overlayView = overlayView,
                    onSaved = onPhotoSaved
                )
            }
        ) {
            Icon(Icons.Filled.CameraAlt, null)
        }
    }
}


fun takePhotoWithOverlay(
    context: Context,
    imageCapture: ImageCapture,
    overlayView: View,
    onSaved: () -> Unit
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {

            override fun onCaptureSuccess(image: ImageProxy) {

                val cameraBitmap = image.toBitmapWithRotation()
                val overlayBitmap = overlayView.drawToBitmap()

                val scale = max(
                    cameraBitmap.width / overlayBitmap.width.toFloat(),
                    cameraBitmap.height / overlayBitmap.height.toFloat()
                )

                val scaledOverlay = Bitmap.createScaledBitmap(
                    overlayBitmap,
                    (overlayBitmap.width * scale).toInt(),
                    (overlayBitmap.height * scale).toInt(),
                    true
                )

                val xOffset = (scaledOverlay.width - cameraBitmap.width) / 2
                val yOffset = (scaledOverlay.height - cameraBitmap.height) / 2

                val croppedOverlay = Bitmap.createBitmap(
                    scaledOverlay,
                    xOffset,
                    yOffset,
                    cameraBitmap.width,
                    cameraBitmap.height
                )

                val result = Bitmap.createBitmap(
                    cameraBitmap.width,
                    cameraBitmap.height,
                    Bitmap.Config.ARGB_8888
                )

                val canvas = Canvas(result)
                canvas.drawBitmap(cameraBitmap, 0f, 0f, null)
                canvas.drawBitmap(croppedOverlay, 0f, 0f, null)

                saveBitmapToGallery(context, result)

                onSaved()   
                image.close()
            }
        }
    )
}


@Composable
fun StickerCanvas(
    stickers: List<StickerState>,
    onRequestDelete: (StickerState) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        stickers.forEach { sticker ->
            Image(
                painter = painterResource(sticker.resId),
                contentDescription = null,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            sticker.offsetX.toInt(),
                            sticker.offsetY.toInt()
                        )
                    }
                    .graphicsLayer {
                        scaleX = sticker.scale
                        scaleY = sticker.scale
                    }
                    .size(96.dp)

                    .pointerInput(sticker.id) {
                        detectTapGestures(
                            onLongPress = {
                                onRequestDelete(sticker)
                            }
                        )
                    }

                    .pointerInput(sticker.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            sticker.offsetX += pan.x
                            sticker.offsetY += pan.y
                            sticker.scale =
                                (sticker.scale * zoom)
                                    .coerceIn(0.3f, 5f)
                        }
                    }
            )
        }
    }
}


@Composable
fun StickerPanel(
    modifier: Modifier,
    onStickerSelected: (Int) -> Unit
) {
    val stickers = listOf(
        R.drawable.sticker_01,
        R.drawable.sticker_02,
        R.drawable.sticker_03,
        R.drawable.sticker_04
    )

    Box(
        modifier = modifier
            .width(72.dp)
            .background(Color.Black.copy(alpha = 0.4f))
    ) {
        LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
            items(stickers) { resId ->
                Image(
                    painter = painterResource(resId),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(48.dp)
                        .clickable { onStickerSelected(resId) }
                )
            }
        }
    }
}


fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    stickers: List<StickerState>,
    previewView: PreviewView
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {

            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmapWithRotation()
                val merged =
                    mergeStickerToBitmap(context, bitmap, stickers, previewView)
                saveBitmapToGallery(context, merged)
                image.close()
            }
        }
    )
}

fun ImageProxy.toBitmapWithRotation(): Bitmap {
    val bitmap = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap

    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
    }
    return Bitmap.createBitmap(
        bitmap, 0, 0,
        bitmap.width, bitmap.height,
        matrix, true
    )
}

fun mergeStickerToBitmap(
    context: Context,
    original: Bitmap,
    stickers: List<StickerState>,
    previewView: PreviewView
): Bitmap {

    val result =
        Bitmap.createBitmap(
            original.width,
            original.height,
            Bitmap.Config.ARGB_8888
        )

    val canvas = Canvas(result)
    canvas.drawBitmap(original, 0f, 0f, null)

    stickers.forEach { sticker ->
        val bmp =
            BitmapFactory.decodeResource(context.resources, sticker.resId)

        val matrix = Matrix()
        matrix.postTranslate(sticker.offsetX, sticker.offsetY)
        matrix.postScale(sticker.scale, sticker.scale)

        canvas.drawBitmap(bmp, matrix, null)
    }

    return result
}


fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "sticker_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/StickerCamera"
        )
    }

    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: return

    context.contentResolver.openOutputStream(uri)?.use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
    }
}
